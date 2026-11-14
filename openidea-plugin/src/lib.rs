//! OpenIDEA Commit Generator: writes an OpenCode-made commit message into the
//! commit panel.
//!
//! The `generate` command is contributed to `commit.message` (above the message
//! box in the Commit tool window and dialog). It reads the pending commit
//! (`vcs.read`), assembles the checked diffs, asks the local OpenCode CLI
//! (`process:opencode`), cleans the answer and writes it back (`vcs.write`).
//! The write is revision-guarded, so text the user typed during generation is
//! never overwritten; that case ends with a warning instead. Handled failures
//! notify once and return `Ok(None)`; a user cancellation returns
//! `Error::Cancelled`.

mod cleaner;
mod diff;
mod opencode;
mod prompt;

pub use opencode::Generator;
use openidea_plugin::{
    Error, Invocation, LogLevel, NotificationLevel, Plugin, host, register_plugin,
};

const TITLE: &str = "OpenIDEA Commit Generator";
const COMMAND: &str = "generate";
const DEFAULT_MODEL: &str = "opencode-go/longcat-2.5-free";
const MODELS: [&str; 2] = [DEFAULT_MODEL, "opencode-go/deepseek-v4.1-flash"];
/// Settings path shown when a capability is missing.
const SETTINGS_HINT: &str = "Settings → Plugins → 权限";

/// The plugin; `generator` is the production CLI call unless a test replaces it.
pub struct CommitPlugin {
    generator: Box<dyn Generator>,
}

impl Default for CommitPlugin {
    fn default() -> Self {
        Self {
            generator: Box::new(opencode::OpenCodeGenerator),
        }
    }
}

impl CommitPlugin {
    /// A plugin with a different generator, for tests.
    pub fn with_generator(generator: Box<dyn Generator>) -> Self {
        Self { generator }
    }

    /// One generation pass; a task keeps the call budget alive while OpenCode runs.
    fn generate(&mut self) -> Result<(), Error> {
        let task = host::begin_task("Generating commit message", true);
        task.report(0, None, Some("读取待提交变更…"));

        let draft = match host::pending_commit() {
            Ok(Some(draft)) => draft,
            Ok(None) => {
                warn("当前项目没有 Git 仓库");
                return Ok(());
            }
            Err(Error::Cancelled) => return Err(Error::Cancelled),
            Err(error) => {
                fail(&error, "读取待提交变更失败");
                return Ok(());
            }
        };
        if draft.changes.is_empty() {
            warn("Commit 面板没有勾选要提交的变更");
            return Ok(());
        }

        let Some(assembled) = diff::assemble(&draft.changes) else {
            warn("没有可用的文本 diff");
            return Ok(());
        };
        if assembled.truncated {
            warn("待提交 diff 超过 1 MiB，提示词已按截断内容生成");
        }

        let prompt = prompt::build(&assembled.text);
        let model = selected_model();
        host::log(
            LogLevel::Info,
            &format!("generating a commit message with model {model}"),
        );
        task.report(0, None, Some("等待 OpenCode 生成…"));
        let raw = match self.generator.generate(&prompt, &model, &task) {
            Ok(raw) => raw,
            Err(Error::Cancelled) => return Err(Error::Cancelled),
            Err(error) => {
                fail(&error, "Commit Message 生成失败");
                return Ok(());
            }
        };

        let message = cleaner::clean(&raw);
        if message.is_empty() {
            warn("OpenCode 未返回有效 Commit Message");
            return Ok(());
        }
        match host::set_commit_message(draft.revision, &message) {
            Ok(()) => {
                info("Commit Message 生成成功");
                Ok(())
            }
            // Never overwrite what the user typed or changed during generation.
            Err(Error::Stale) => {
                warn("提交消息或勾选变更已变化，本次生成结果未写入");
                Ok(())
            }
            Err(Error::Cancelled) => Err(Error::Cancelled),
            Err(error) => {
                fail(&error, "写入提交消息失败");
                Ok(())
            }
        }
    }
}

impl Plugin for CommitPlugin {
    fn run_command(
        &mut self,
        id: &str,
        _ctx: &Invocation,
        _arg: Option<&str>,
    ) -> Result<Option<String>, Error> {
        if id != COMMAND {
            return Err(Error::NotFound(id.to_owned()));
        }
        self.generate().map(|()| None)
    }
}

register_plugin!(CommitPlugin);

/// The saved model, falling back to LongCat for values outside the schema.
fn selected_model() -> String {
    host::setting_string("model")
        .filter(|model| MODELS.contains(&model.as_str()))
        .unwrap_or_else(|| DEFAULT_MODEL.to_owned())
}

/// Turns a failed host call into one notification the user can act on.
fn fail(error: &Error, context: &str) {
    let body = match error {
        Error::Denied(capability) => permission_hint(capability),
        Error::Invalid(detail) if is_trust_error(detail) => {
            "请在 OpenIDEA 中信任该项目后重试".to_owned()
        }
        other => format!("{context}：{other}"),
    };
    host::notify(NotificationLevel::Error, TITLE, &body);
}

fn permission_hint(capability: &str) -> String {
    let mut hint = format!("缺少权限 {capability}。请在 {SETTINGS_HINT} 中授权后重试。");
    if capability.starts_with("process:") {
        // A .ps1 is not an executable; the grant needs opencode.exe.
        hint.push_str("请选择 opencode.exe；.ps1 不是可执行程序。");
    }
    hint
}

fn is_trust_error(detail: &str) -> bool {
    let lower = detail.to_lowercase();
    lower.contains("trust") || detail.contains("受信")
}

fn warn(body: &str) {
    host::notify(NotificationLevel::Warning, TITLE, body);
}

fn info(body: &str) {
    host::notify(NotificationLevel::Info, TITLE, body);
}
