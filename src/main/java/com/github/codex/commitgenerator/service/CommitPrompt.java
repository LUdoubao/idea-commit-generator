package com.github.codex.commitgenerator.service;

import org.jetbrains.annotations.NotNull;

/**
 * Builds the fixed, built-in commit prompt for this standalone plugin.
 * No global or project-specific prompt files are read or appended.
 */
public final class CommitPrompt {

    /** Fixed Chinese-language commit policy adapted from the established CC GUI prompt. */
    private static final String BUILT_IN = """
            你是一名资深软件工程师，请基于下方 git diff 撰写一条高质量的 Git commit message，遵循 Conventional Commits 规范。

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
            """;

    /** Joins the fixed policy and selected diff into the sole OpenCode user message. */
    @NotNull
    public String build(@NotNull String diff) {
        // Keep the diff in a fenced block so file content cannot be confused with prompt policy.
        return BUILT_IN + "\n\n---\n\n以下是 git diff，请据此生成 commit message：\n\n"
                + "```diff\n" + diff + "\n```\n\n"
                + "必须使用 <commit> 和 </commit> 标签包裹完整结果。\n";
    }
}
