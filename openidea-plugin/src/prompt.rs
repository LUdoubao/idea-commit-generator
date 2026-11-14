//! The fixed commit-message prompt.
//!
//! Mirrors the IntelliJ plugin's built-in policy: no project or global prompt
//! files are read, and the diff goes into a fenced block so file content cannot
//! be mistaken for instructions. Needs no capability; called from the
//! `generate` command before the process starts.

/// Fixed Chinese-language policy adapted from the established CC GUI prompt.
const BUILT_IN: &str = r#"你是一名资深软件工程师，请基于下方 git diff 撰写一条高质量的 Git commit message，遵循 Conventional Commits 规范。

输出格式：
<type>[scope]: <description>

<body>

[footer]

提交类型：feat, fix, docs, style, refactor, perf, test, build, ci, chore, revert

要求：
- commit message 的主题描述、正文和说明均使用简体中文；type、scope、文件名、代码符号及标准页脚标记按原样保留。
- 主题行：祈使语气、现在时，不超过 72 字符，末尾不加句号。
- 正文（当改动多于一个逻辑点时必须写）：总结改了什么和为什么改，不要逐行复述 diff。把相关改动归纳成要点，每条以 "- " 开头，并提及主要涉及的文件/模块/区域。
- 每行不超过 72 字符。
- 页脚：如适用，写 BREAKING CHANGE 与 issue 引用（Closes: #123）。
- 只输出 commit message 本身：不要前缀分析、解释、Generated with、Co-Authored-By 或 emoji。
- 必须用 <commit></commit> 标签包裹整条消息，标签外不要有任何内容。
"#;

/// Joins the fixed policy and the assembled diff into the sole OpenCode message.
pub fn build(diff: &str) -> String {
    // The fence keeps diff text from being read as prompt policy.
    format!(
        "{BUILT_IN}\n\n---\n\n以下是 git diff，请据此生成 commit message：\n\n```diff\n{diff}\n```\n\n必须使用 <commit> 和 </commit> 标签包裹完整结果。\n"
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keeps_policy_and_fences_the_diff() {
        let prompt = build("diff --git a/a b/a\n+新增\n");
        assert!(prompt.contains("Conventional Commits"));
        assert!(prompt.contains("必须用 <commit></commit> 标签包裹整条消息"));
        assert!(prompt.contains("```diff\ndiff --git a/a b/a\n+新增\n\n```"));
        assert!(
            prompt
                .trim_end()
                .ends_with("必须使用 <commit> 和 </commit> 标签包裹完整结果。")
        );
    }

    #[test]
    fn forbids_tags_outside_the_wrapped_message() {
        let prompt = build("x");
        assert!(prompt.contains("标签外不要有任何内容"));
        assert!(prompt.contains("不要前缀分析、解释、Generated with、Co-Authored-By 或 emoji"));
    }
}
