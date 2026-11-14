# OpenIDEA Commit Generator

OpenIDEA 0.1.44 插件（插件 API 1.7）：从 Commit 面板读取本次勾选的变更与 diff，调用本机 OpenCode CLI 生成 Conventional Commits 消息，并写回提交消息框。与仓库根目录的 IntelliJ IDEA 插件并存，两者不共享设置与运行状态。

## 组成

| 文件 | 作用 |
|---|---|
| `plugin.toml` | 清单：`generate` 命令、`commit.message` 菜单、设置页、权限 |
| `settings.schema.json` | `model` 设置：`opencode-go/longcat-2.5-free`（默认）、`opencode-go/deepseek-v4.1-flash` |
| `src/lib.rs` | 命令编排、task 生命周期、错误到通知的映射 |
| `src/prompt.rs` | 固定提示词与 diff 围栏 |
| `src/diff.rs` | 勾选变更转 diff 文本、二进制合成头、截断说明 |
| `src/opencode.rs` | `Generator` trait 与 OpenCode 进程调用、JSON 事件解析 |
| `src/cleaner.rs` | `<commit>` 标签与代码围栏清洗 |
| `tests/command.rs` | MockHost 集成测试 |

## 前置条件

- Rust 1.85 及以上（`edition 2024`）。
- `openidea-plugin` 工具：
  - `cargo install --locked --git https://github.com/l0ng-ai/openidea --tag v0.1.44 ide-host --bin openidea-plugin`
- 本机已安装并登录 OpenCode CLI：
  - `opencode auth login`
  - 已验证版本：`opencode v2.0.16`

## 构建与校验

```sh
rustup target add wasm32-wasip2
cargo test -- --test-threads=1
openidea-plugin build
openidea-plugin check .
openidea-plugin pack --out dist/openidea-commit-generator.oiplugin
```

`cargo test` 使用 SDK 的 MockHost，不启动真实 OpenCode；`--test-threads=1` 避免同一进程内的 mock 状态互相影响。

## 安装与授权

1. Settings → Plugins → Install Plugin from Package File… 选择 `dist/openidea-commit-generator.oiplugin`。
2. 安装时批准 `vcs.read`、`vcs.write`、`process:opencode`。
3. Settings → Plugins → 本插件 → 权限：为 `process:opencode` 选择 `opencode.exe`。`.ps1` 不是可执行程序，不能被授予；本机可用路径示例：
   `D:\environment\nvm\nvm\v22.23.2\node_modules\@opencode\cli\bin\opencode.exe`。
4. OpenCode 升级或替换后，需要重新批准该可执行文件。

## 使用

1. 在受信任的项目中打开 Commit 工具窗口或提交对话框，勾选要提交的变更。
2. 点击消息框上方的插件图标（命令：`Generate Commit Message`，分类 `Commit`）。
3. 生成期间可在后台任务面板取消；取消、超时（120 秒）或输出超过 4 MiB 时会结束 OpenCode 子进程，提交消息不变。

## 行为说明

- 读取勾选变更时，IDE 会先保存这些文件在编辑器中未保存的内容，与正常提交流程一致。
- diff 总量上限 1 MiB；超出时宿主返回截断结果，提示词末尾追加“注意：本次 diff 因超过 1 MiB 被截断。”，并出现一次警告通知，生成继续。
- 只有二进制变更时没有可用文本，直接警告并结束，不调用模型。
- 写回带 revision 保护：生成期间用户输入了文字或改动了勾选变更时返回 stale，不覆盖用户内容，只给出一条“未写入”警告。
- 子进程工作目录为插件私有数据目录，不加载项目级 `opencode.json` 或 `AGENTS.md`；全局配置与登录状态仍生效。
- 模型设置非法时回退到 `opencode-go/longcat-2.5-free`。

## 卸载与回滚

- Settings → Plugins → 本插件 → Disable / Uninstall；卸载会移除插件、设置与其私有目录。
- 开发链接模式：`openidea-plugin unlink .`，随后可删除 `dist/`。
- 本插件只新增 `openidea-plugin/` 目录，删除该目录即可回滚，不影响现有 IDEA 插件与 Gradle 构建。

## 已知限制

- 仅支持 OpenIDEA 0.1.44（插件 API 1.7）；0.1.39–0.1.43 不在支持范围。
- 连续点击两次会排队执行第二次生成；revision 保护确保不覆盖用户手动输入，但会产生一次额外的模型调用。
- OpenCode 的 JSON 事件结构变化可能导致解析不到文本；失败时提示会附带 stderr 末尾，可用 `openidea-plugin` 的 IDE 日志定位。
