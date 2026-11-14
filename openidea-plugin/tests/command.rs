//! Command-level tests against the SDK's mock host.

use std::cell::RefCell;
use std::rc::Rc;

use openidea_commit_generator::{CommitPlugin, Generator};
use openidea_plugin::mock::MockHost;
use openidea_plugin::{CommitChange, CommitDraft, Error, NotificationLevel, Plugin, Task};

const CAPABILITIES: [&str; 3] = ["vcs.read", "vcs.write", "process:opencode"];
const SETTINGS_PATH: &str = "Settings → Plugins → 权限";

type Calls = Rc<RefCell<Vec<(String, String)>>>;

struct FakeGenerator {
    response: Result<String, Error>,
    calls: Calls,
    during: Option<Box<dyn FnMut()>>,
}

impl FakeGenerator {
    fn new(response: &str, calls: &Calls) -> Self {
        Self {
            response: Ok(response.to_owned()),
            calls: Rc::clone(calls),
            during: None,
        }
    }

    fn failing(error: Error, calls: &Calls) -> Self {
        Self {
            response: Err(error),
            calls: Rc::clone(calls),
            during: None,
        }
    }

    /// Runs while generation is in progress, like the user typing meanwhile.
    fn during(mut self, during: impl FnMut() + 'static) -> Self {
        self.during = Some(Box::new(during));
        self
    }
}

impl Generator for FakeGenerator {
    fn generate(&mut self, prompt: &str, model: &str, _task: &Task) -> Result<String, Error> {
        self.calls
            .borrow_mut()
            .push((prompt.to_owned(), model.to_owned()));
        if let Some(during) = self.during.as_mut() {
            during();
        }
        self.response.clone()
    }
}

fn mock(capabilities: &[&str]) -> Rc<MockHost> {
    Rc::new(MockHost::install().with_capabilities(capabilities))
}

fn change(path: &str, diff: Option<&str>, truncated: bool) -> CommitChange {
    CommitChange {
        repository: "/repo".to_owned(),
        path: path.to_owned(),
        old_path: None,
        status: "modified".to_owned(),
        diff: diff.map(str::to_owned),
        truncated,
    }
}

fn draft(changes: Vec<CommitChange>) -> CommitDraft {
    CommitDraft {
        revision: 4,
        message: "旧消息".to_owned(),
        amend: false,
        changes,
    }
}

fn run(host: &MockHost, plugin: &mut CommitPlugin) -> Result<Option<String>, Error> {
    let ctx = host.invocation();
    plugin.run_command("generate", &ctx, None)
}

fn bodies(host: &MockHost, level: NotificationLevel) -> Vec<String> {
    host.notifications()
        .into_iter()
        .filter(|n| n.level == level)
        .map(|n| n.body)
        .collect()
}

#[test]
fn writes_the_cleaned_message_with_the_saved_model() {
    let host = mock(&CAPABILITIES);
    host.set_setting("model", "\"opencode-go/deepseek-v4.1-flash\"");
    host.set_commit_draft(Some(draft(vec![
        change(
            "/repo/src/a.rs",
            Some("diff --git a/src/a.rs b/src/a.rs\n+一\n"),
            false,
        ),
        change(
            "/repo/src/b.rs",
            Some("diff --git a/src/b.rs b/src/b.rs\n+二\n"),
            false,
        ),
    ])));
    let calls: Calls = Calls::default();
    let mut plugin = CommitPlugin::with_generator(Box::new(FakeGenerator::new(
        "<commit>feat: 新增提交生成</commit>",
        &calls,
    )));

    assert_eq!(run(&host, &mut plugin).unwrap(), None);

    {
        let recorded = calls.borrow();
        assert_eq!(recorded.len(), 1);
        assert!(recorded[0].0.contains("Conventional Commits"));
        assert!(recorded[0].0.contains("diff --git a/src/a.rs b/src/a.rs"));
        assert_eq!(recorded[0].1, "opencode-go/deepseek-v4.1-flash");
    }
    assert_eq!(host.commit_draft().unwrap().message, "feat: 新增提交生成");
    assert!(
        bodies(&host, NotificationLevel::Info)
            .iter()
            .any(|body| body.contains("生成成功"))
    );
}

#[test]
fn keeps_manual_input_when_the_draft_moves_on() {
    let host = mock(&CAPABILITIES);
    host.set_commit_draft(Some(draft(vec![change(
        "/repo/src/a.rs",
        Some("diff\n"),
        false,
    )])));
    let calls: Calls = Calls::default();
    let typed = Rc::clone(&host);
    let generator = FakeGenerator::new("<commit>feat: 生成</commit>", &calls)
        .during(move || typed.edit_commit_message("用户输入"));
    let mut plugin = CommitPlugin::with_generator(Box::new(generator));

    assert_eq!(run(&host, &mut plugin).unwrap(), None);

    assert_eq!(host.commit_draft().unwrap().message, "用户输入");
    assert!(
        bodies(&host, NotificationLevel::Warning)
            .iter()
            .any(|body| body.contains("未写入"))
    );
}

#[test]
fn warns_without_included_changes_and_skips_the_model() {
    let host = mock(&CAPABILITIES);
    host.set_commit_draft(Some(draft(Vec::new())));
    let calls: Calls = Calls::default();
    let mut plugin = CommitPlugin::with_generator(Box::new(FakeGenerator::new("x", &calls)));

    assert_eq!(run(&host, &mut plugin).unwrap(), None);

    assert!(calls.borrow().is_empty());
    assert_eq!(host.commit_draft().unwrap().message, "旧消息");
    assert!(
        bodies(&host, NotificationLevel::Warning)
            .iter()
            .any(|body| body.contains("没有勾选"))
    );
}

#[test]
fn continues_with_a_truncated_diff_and_warns_once() {
    let host = mock(&CAPABILITIES);
    host.set_commit_draft(Some(draft(vec![change(
        "/repo/src/a.rs",
        Some("diff --git a/src/a.rs b/src/a.rs\n+一\n"),
        true,
    )])));
    let calls: Calls = Calls::default();
    let mut plugin = CommitPlugin::with_generator(Box::new(FakeGenerator::new(
        "<commit>fix: 修复</commit>",
        &calls,
    )));

    assert_eq!(run(&host, &mut plugin).unwrap(), None);

    assert!(
        calls.borrow()[0]
            .0
            .contains("注意：本次 diff 因超过 1 MiB 被截断。")
    );
    assert_eq!(host.commit_draft().unwrap().message, "fix: 修复");
    let warnings: Vec<_> = bodies(&host, NotificationLevel::Warning)
        .into_iter()
        .filter(|body| body.contains("1 MiB"))
        .collect();
    assert_eq!(warnings.len(), 1);
}

#[test]
fn explains_a_missing_permission() {
    let host = mock(&["vcs.write"]);
    host.set_commit_draft(Some(draft(vec![change(
        "/repo/src/a.rs",
        Some("diff\n"),
        false,
    )])));
    let calls: Calls = Calls::default();
    let mut plugin = CommitPlugin::with_generator(Box::new(FakeGenerator::new("x", &calls)));

    assert_eq!(run(&host, &mut plugin).unwrap(), None);

    assert!(calls.borrow().is_empty());
    let errors = bodies(&host, NotificationLevel::Error);
    assert!(
        errors
            .iter()
            .any(|body| body.contains("vcs.read") && body.contains(SETTINGS_PATH))
    );
}

#[test]
fn synthesizes_headers_for_binary_changes() {
    let host = mock(&CAPABILITIES);
    host.set_commit_draft(Some(draft(vec![
        change(
            "/repo/src/a.rs",
            Some("diff --git a/src/a.rs b/src/a.rs\n+一\n"),
            false,
        ),
        change("/repo/assets/logo.png", None, false),
    ])));
    let calls: Calls = Calls::default();
    let mut plugin = CommitPlugin::with_generator(Box::new(FakeGenerator::new(
        "<commit>chore: 更新图片</commit>",
        &calls,
    )));

    assert_eq!(run(&host, &mut plugin).unwrap(), None);

    assert!(
        calls.borrow()[0]
            .0
            .contains("Binary files a/assets/logo.png and b/assets/logo.png differ")
    );
    assert_eq!(host.commit_draft().unwrap().message, "chore: 更新图片");
}

#[test]
fn propagates_cancellation_without_touching_the_message() {
    let host = mock(&CAPABILITIES);
    host.set_commit_draft(Some(draft(vec![change(
        "/repo/src/a.rs",
        Some("diff\n"),
        false,
    )])));
    let calls: Calls = Calls::default();
    let mut plugin =
        CommitPlugin::with_generator(Box::new(FakeGenerator::failing(Error::Cancelled, &calls)));

    assert_eq!(run(&host, &mut plugin).unwrap_err(), Error::Cancelled);

    assert_eq!(host.commit_draft().unwrap().message, "旧消息");
}
