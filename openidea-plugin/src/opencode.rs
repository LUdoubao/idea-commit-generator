//! Generates a message with the local OpenCode CLI.
//!
//! The approved `process:opencode` program runs `run --format json --model …`
//! in the plugin's private data directory (`cwd = None`), so project-level
//! `opencode.json` or `AGENTS.md` cannot change the fixed prompt, while global
//! config and login still come from the user's environment. The prompt goes to
//! stdin; JSON events are read from stdout. Every 1.5 s the task reports and
//! checks cancellation to renew its 60 s call budget; 120 s is the hard limit.
//! Cancellation, timeout and output overflow kill the child.

use std::time::{Duration, Instant};

use openidea_plugin::host::{self, Child, Command};
use openidea_plugin::{Error, Task};
use serde_json::Value;

/// Produces the raw assistant text for a prompt; tests inject a fake.
pub trait Generator {
    /// `task` keeps the call budget alive and reports cancellation.
    fn generate(&mut self, prompt: &str, model: &str, task: &Task) -> Result<String, Error>;
}

/// The production generator: one `opencode` process per call.
#[derive(Default)]
pub struct OpenCodeGenerator;

/// Program name of the `process:opencode` grant; the host resolves its path.
const PROGRAM: &str = "opencode";
/// Hard limit for one generation.
const TIMEOUT: Duration = Duration::from_secs(120);
/// Poll interval, short enough to renew the task's call budget.
const POLL_MS: u32 = 1_500;
/// Most stdout kept before the process is killed.
const MAX_STDOUT: usize = 4 << 20;
/// stderr kept for failure messages.
const MAX_STDERR_TAIL: usize = 800;
/// Most bytes one `write` puts into stdin (the host allows 1 MiB per call).
const MAX_WRITE: usize = 64 << 10;
const PROGRESS: &str = "等待 OpenCode 生成…";

impl Generator for OpenCodeGenerator {
    fn generate(&mut self, prompt: &str, model: &str, task: &Task) -> Result<String, Error> {
        if task.is_cancelled() {
            return Err(Error::Cancelled);
        }
        // The model stays in argv; the possibly large prompt stays on stdin.
        let command = Command::new(PROGRAM).args(["run", "--format", "json", "--model", model]);
        let child = host::start(&command, None).map_err(start_error)?;
        if let Err(error) = write_prompt(&child, prompt) {
            let _ = child.kill();
            return Err(error);
        }
        if let Err(error) = child.close_stdin() {
            let _ = child.kill();
            return Err(Error::Failed(format!("无法结束 OpenCode 输入：{error}")));
        }

        let started = Instant::now();
        let mut stdout = String::new();
        let mut stderr = String::new();
        loop {
            if task.is_cancelled() {
                let _ = child.kill();
                return Err(Error::Cancelled);
            }
            // Renews the call budget before the next wait.
            task.report(0, None, Some(PROGRESS));
            let chunk = match child.wait(POLL_MS) {
                Ok(chunk) => chunk,
                Err(error) => {
                    let _ = child.kill();
                    return Err(error);
                }
            };
            stdout.push_str(&chunk.stdout);
            stderr.push_str(&chunk.stderr);
            keep_tail(&mut stderr, MAX_STDERR_TAIL);
            if stdout.len() > MAX_STDOUT {
                let _ = child.kill();
                return Err(Error::Failed(format!(
                    "OpenCode 输出超过 {} MiB，已终止",
                    MAX_STDOUT >> 20
                )));
            }
            if let Some(status) = chunk.status {
                return finish(status, &stdout, &stderr);
            }
            if started.elapsed() >= TIMEOUT {
                let _ = child.kill();
                return Err(Error::Failed(format!(
                    "OpenCode 生成超时（{} 秒）",
                    TIMEOUT.as_secs()
                )));
            }
        }
    }
}

/// Keeps capability and trust failures intact so the command can explain them.
fn start_error(error: Error) -> Error {
    if matches!(
        &error,
        Error::Denied(_) | Error::Invalid(_) | Error::Cancelled
    ) {
        error
    } else {
        Error::Failed(format!("无法启动 OpenCode：{error}"))
    }
}

/// Writes the prompt in bounded pieces; the host rejects a single write above 1 MiB.
fn write_prompt(child: &Child, prompt: &str) -> Result<(), Error> {
    for chunk in chunks(prompt, MAX_WRITE) {
        child
            .write(chunk)
            .map_err(|error| Error::Failed(format!("写入 OpenCode 输入失败：{error}")))?;
    }
    Ok(())
}

/// Splits `text` at UTF-8 boundaries into pieces of at most `max` bytes.
fn chunks(text: &str, max: usize) -> Vec<&str> {
    let mut out = Vec::new();
    let mut start = 0;
    while start < text.len() {
        let mut end = (start + max.max(1)).min(text.len());
        while end > start && !text.is_char_boundary(end) {
            end -= 1;
        }
        if end == start {
            end = start + text[start..].chars().next().map_or(1, char::len_utf8);
        }
        out.push(&text[start..end]);
        start = end;
    }
    out
}

/// Keeps the last `max` bytes of `text`, without cutting a character.
fn keep_tail(text: &mut String, max: usize) {
    if text.len() <= max {
        return;
    }
    let mut cut = text.len() - max;
    while cut < text.len() && !text.is_char_boundary(cut) {
        cut += 1;
    }
    text.drain(..cut);
}

/// Maps the exit status and the collected streams to the raw assistant text.
fn finish(status: i32, stdout: &str, stderr: &str) -> Result<String, Error> {
    let parsed = parse_output(stdout);
    if !parsed.error.is_empty() {
        return Err(Error::Failed(parsed.error));
    }
    let tail = if stderr.trim().is_empty() {
        String::new()
    } else {
        format!("：{}", stderr.trim())
    };
    if status != 0 {
        return Err(Error::Failed(format!("OpenCode 退出码 {status}{tail}")));
    }
    if parsed.text.trim().is_empty() {
        return Err(Error::Failed(format!("OpenCode 未返回有效文本{tail}")));
    }
    Ok(parsed.text)
}

struct ParsedOutput {
    text: String,
    error: String,
}

/// Concatenates text events, ignores reasoning events and keeps the last error.
fn parse_output(stdout: &str) -> ParsedOutput {
    let mut text = String::new();
    let mut error = String::new();
    for line in stdout.lines() {
        if line.trim().is_empty() {
            continue;
        }
        let Ok(event) = serde_json::from_str::<Value>(line) else {
            continue;
        };
        let Some(event) = event.as_object() else {
            continue;
        };
        let kind = string_value(event.get("type")).to_lowercase();
        if kind == "error" || kind.ends_with(".error") {
            error = error_message(event);
            continue;
        }
        if kind.contains("reasoning") || kind.contains("think") {
            continue;
        }
        let chunk = text_chunk(event, &kind);
        if !chunk.is_empty() {
            text.push_str(chunk);
        }
    }
    ParsedOutput { text, error }
}

fn text_chunk<'a>(event: &'a serde_json::Map<String, Value>, kind: &str) -> &'a str {
    if !(kind == "text" || kind.contains("delta") || kind.contains("message")) {
        return "";
    }
    if let Some(part) = event.get("part").and_then(Value::as_object) {
        let part_text = string_value(part.get("text"));
        if !part_text.is_empty() {
            return part_text;
        }
    }
    let delta = string_value(event.get("delta"));
    if !delta.is_empty() {
        return delta;
    }
    string_value(event.get("text"))
}

fn error_message(event: &serde_json::Map<String, Value>) -> String {
    match event.get("error") {
        Some(Value::Object(error)) => {
            let direct = string_value(error.get("message"));
            if !direct.is_empty() {
                return direct.to_owned();
            }
            if let Some(Value::Object(data)) = error.get("data") {
                let nested = string_value(data.get("message"));
                if !nested.is_empty() {
                    return nested.to_owned();
                }
            }
            let name = string_value(error.get("name"));
            if name.is_empty() {
                "OpenCode 返回了错误事件".to_owned()
            } else {
                name.to_owned()
            }
        }
        Some(other) => string_value(Some(other)).to_owned(),
        None => String::new(),
    }
}

fn string_value(value: Option<&Value>) -> &str {
    value.and_then(Value::as_str).unwrap_or("")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn concatenates_text_and_delta_and_skips_reasoning() {
        let stdout = concat!(
            r#"{"type":"text","part":{"text":"feat: "}}"#,
            "\n",
            r#"{"type":"reasoning","delta":"想一下"}"#,
            "\n",
            r#"{"type":"delta","delta":"新增提交生成"}"#,
            "\n",
            r#"{"type":"message","text":"\n正文"}"#,
            "\n",
            "not json\n",
        );
        let parsed = parse_output(stdout);
        assert_eq!(parsed.text, "feat: 新增提交生成\n正文");
        assert!(parsed.error.is_empty());
    }

    #[test]
    fn keeps_the_error_event_as_failure_detail() {
        let parsed = parse_output(r#"{"type":"error","error":{"message":"模型不可用"}}"#);
        assert!(parsed.text.is_empty());
        assert_eq!(parsed.error, "模型不可用");
    }

    #[test]
    fn reads_nested_error_data() {
        let parsed =
            parse_output(r#"{"type":"session.error","error":{"data":{"message":"未登录"}}}"#);
        assert_eq!(parsed.error, "未登录");
    }

    #[test]
    fn splits_large_prompts_at_character_boundaries() {
        let text = "a你b好c".repeat(10_000);
        let pieces = chunks(&text, 4);
        assert!(pieces.iter().all(|piece| piece.len() <= 4));
        assert_eq!(pieces.concat(), text);
    }

    #[test]
    fn keeps_the_stderr_tail_whole() {
        let mut tail = "错误".repeat(600);
        keep_tail(&mut tail, MAX_STDERR_TAIL);
        assert!(tail.len() <= MAX_STDERR_TAIL);
        assert!(tail.chars().all(|c| c == '错' || c == '误'));
    }

    #[test]
    fn reports_exit_status_and_empty_output() {
        assert!(
            matches!(finish(2, "", "启动失败"), Err(Error::Failed(message)) if message.contains("退出码 2"))
        );
        assert!(finish(0, r#"{"type":"reasoning","delta":"x"}"#, "").is_err());
        assert_eq!(
            finish(0, r#"{"type":"text","text":"chore: ok"}"#, "").unwrap(),
            "chore: ok"
        );
    }
}
