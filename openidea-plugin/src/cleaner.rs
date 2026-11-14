//! Extracts a user-ready commit message from OpenCode's text response.
//!
//! The `<commit>` tag contract is preferred; a Markdown code fence and finally
//! the trimmed text support models that ignore the output-only instruction.
//! Needs no capability.

const OPEN_TAG: &str = "<commit>";
const CLOSE_TAG: &str = "</commit>";

/// The cleaned message, or an empty string when the model emitted no usable text.
pub fn clean(raw: &str) -> String {
    let text = raw.trim();
    if text.is_empty() {
        return String::new();
    }

    // Honor the prompt contract: only the first complete tag pair is the answer.
    if let Some(start) = text.find(OPEN_TAG) {
        let body_start = start + OPEN_TAG.len();
        if let Some(end) = text[body_start..]
            .find(CLOSE_TAG)
            .map(|offset| body_start + offset)
        {
            return text[body_start..end].trim().to_owned();
        }
    }

    // Some compatible models wrap a plain answer in Markdown anyway.
    if let Some(fence_start) = text.find("```") {
        if let Some(body_start) = text[fence_start..]
            .find('\n')
            .map(|offset| fence_start + offset)
        {
            if let Some(fence_end) = text[body_start + 1..]
                .find("```")
                .map(|offset| body_start + 1 + offset)
            {
                return text[body_start + 1..fence_end].trim().to_owned();
            }
        }
    }
    text.to_owned()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn prefers_the_commit_tag_pair() {
        assert_eq!(
            clean(" 前文 <commit>\nfeat: 新增\n\n正文\n</commit> 后文 "),
            "feat: 新增\n\n正文"
        );
    }

    #[test]
    fn reads_the_first_complete_pair() {
        assert_eq!(clean("<commit>一</commit><commit>二</commit>"), "一");
    }

    #[test]
    fn falls_back_to_a_code_fence() {
        assert_eq!(
            clean("结果：\n```text\nfix: 修复时区\n```\n"),
            "fix: 修复时区"
        );
    }

    #[test]
    fn keeps_plain_text() {
        assert_eq!(clean("  chore: 更新依赖 \n"), "chore: 更新依赖");
    }

    #[test]
    fn ignores_unclosed_markup() {
        assert_eq!(clean("<commit>未闭合"), "<commit>未闭合");
        assert_eq!(clean("```\n未闭合围栏"), "```\n未闭合围栏");
    }

    #[test]
    fn empty_input_stays_empty() {
        assert_eq!(clean("  \n\t"), "");
    }
}
