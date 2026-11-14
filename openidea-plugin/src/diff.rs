//! Turns the commit panel's included changes into one diff text.
//!
//! The host returns per-file `git diff` output restricted to the checked blocks
//! (1 MiB in all, then `truncated`), so text diffs are appended verbatim. A
//! change without a diff (binary content) gets a synthetic header. One
//! truncated change appends the truncation notice and is reported to the user;
//! generation still goes on. Needs no capability.

use openidea_plugin::CommitChange;

/// Appended to the diff when the host cut it at 1 MiB.
pub const TRUNCATED_NOTICE: &str = "注意：本次 diff 因超过 1 MiB 被截断。";

#[derive(Debug, PartialEq, Eq)]
pub struct AssembledDiff {
    pub text: String,
    pub truncated: bool,
}

/// The diff text for `changes`, or `None` when no change has a usable text diff.
pub fn assemble(changes: &[CommitChange]) -> Option<AssembledDiff> {
    let mut text = String::new();
    let mut has_text = false;
    let mut truncated = false;
    for change in changes {
        match change.diff.as_deref() {
            Some(diff) if !diff.is_empty() => {
                has_text = true;
                // The host already limited the blocks; keep the output untouched.
                text.push_str(diff);
            }
            Some(_) => {}
            None => text.push_str(&binary_header(change)),
        }
        truncated |= change.truncated;
    }
    // Only binary changes carry no text the model can use.
    if !has_text {
        return None;
    }
    if truncated {
        if !text.is_empty() && !text.ends_with('\n') {
            text.push('\n');
        }
        text.push_str(TRUNCATED_NOTICE);
        text.push('\n');
    }
    Some(AssembledDiff { text, truncated })
}

/// `diff --git` header pair for a change whose content is binary.
fn binary_header(change: &CommitChange) -> String {
    let path = relative_path(change);
    format!("diff --git a/{path} b/{path}\nBinary files a/{path} and b/{path} differ\n")
}

/// The path inside the repository, with forward slashes, for the synthetic header.
fn relative_path(change: &CommitChange) -> String {
    let path = change.path.replace('\\', "/");
    let repository = change.repository.replace('\\', "/");
    let prefix = format!("{}/", repository.trim_end_matches('/'));
    match path.strip_prefix(&prefix) {
        Some(rest) if !rest.is_empty() => rest.to_owned(),
        _ => path,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn change(path: &str, diff: Option<&str>, truncated: bool) -> CommitChange {
        CommitChange {
            repository: "D:\\repo".to_owned(),
            path: path.to_owned(),
            old_path: None,
            status: "modified".to_owned(),
            diff: diff.map(str::to_owned),
            truncated,
        }
    }

    #[test]
    fn appends_text_diffs_verbatim() {
        let changes = [
            change(
                "D:\\repo\\src\\a.rs",
                Some("diff --git a/src/a.rs b/src/a.rs\n+一\n"),
                false,
            ),
            change(
                "D:\\repo\\src\\b.rs",
                Some("diff --git a/src/b.rs b/src/b.rs\n+二\n"),
                false,
            ),
        ];
        let assembled = assemble(&changes).unwrap();
        assert_eq!(
            assembled.text,
            "diff --git a/src/a.rs b/src/a.rs\n+一\ndiff --git a/src/b.rs b/src/b.rs\n+二\n"
        );
        assert!(!assembled.truncated);
    }

    #[test]
    fn synthesizes_headers_for_binary_changes() {
        let changes = [
            change(
                "D:\\repo\\src\\a.rs",
                Some("diff --git a/src/a.rs b/src/a.rs\n+一\n"),
                false,
            ),
            change("D:\\repo\\assets\\logo.png", None, false),
        ];
        let assembled = assemble(&changes).unwrap();
        assert!(
            assembled
                .text
                .contains("diff --git a/assets/logo.png b/assets/logo.png\n")
        );
        assert!(
            assembled
                .text
                .contains("Binary files a/assets/logo.png and b/assets/logo.png differ\n")
        );
    }

    #[test]
    fn binary_only_changes_have_no_text_diff() {
        let changes = [change("D:\\repo\\assets\\logo.png", None, false)];
        assert_eq!(assemble(&changes), None);
    }

    #[test]
    fn appends_the_truncation_notice_once() {
        let changes = [
            change(
                "D:\\repo\\src\\a.rs",
                Some("diff --git a/src/a.rs b/src/a.rs"),
                true,
            ),
            change(
                "D:\\repo\\src\\b.rs",
                Some("diff --git a/src/b.rs b/src/b.rs"),
                true,
            ),
        ];
        let assembled = assemble(&changes).unwrap();
        assert!(assembled.truncated);
        assert_eq!(assembled.text.matches(TRUNCATED_NOTICE).count(), 1);
        assert!(assembled.text.ends_with(&format!("{TRUNCATED_NOTICE}\n")));
    }

    #[test]
    fn keeps_paths_outside_the_repository_usable() {
        let changes = [
            change("C:\\other\\file.bin", None, false),
            change("D:\\repo\\src\\a.rs", Some("x"), false),
        ];
        let assembled = assemble(&changes).unwrap();
        assert!(
            assembled
                .text
                .contains("Binary files a/C:/other/file.bin and b/C:/other/file.bin differ")
        );
    }
}
