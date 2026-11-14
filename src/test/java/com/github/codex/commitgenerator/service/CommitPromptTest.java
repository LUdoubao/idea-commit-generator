package com.github.codex.commitgenerator.service;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/** Verifies the standalone plugin keeps its fixed Chinese commit prompt contract. */
public class CommitPromptTest {

    @Test
    public void buildsFixedPromptAroundSelectedDiff() {
        String prompt = new CommitPrompt().build("diff --git a/selected.txt b/selected.txt");

        assertTrue(prompt.contains("Conventional Commits"));
        assertTrue(prompt.contains("commit message 的主题描述、正文和说明均使用简体中文"));
        assertTrue(prompt.contains("diff --git a/selected.txt b/selected.txt"));
        assertTrue(prompt.contains("<commit> 和 </commit>"));
        assertTrue(prompt.contains("```diff"));
    }
}
