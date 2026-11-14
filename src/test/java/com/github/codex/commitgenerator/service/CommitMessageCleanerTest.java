package com.github.codex.commitgenerator.service;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Verifies tag, fence, and plain-text response cleanup. */
public class CommitMessageCleanerTest {

    private final CommitMessageCleaner cleaner = new CommitMessageCleaner();

    @Test
    public void extractsCommitTags() {
        assertEquals("fix(core): handle null", cleaner.clean("analysis\n<commit>fix(core): handle null</commit>\n"));
    }

    @Test
    public void extractsMarkdownFenceWhenTagsAreMissing() {
        assertEquals("fix: handle null", cleaner.clean("```text\nfix: handle null\n```"));
    }

    @Test
    public void returnsTrimmedPlainTextAsLastResort() {
        assertEquals("fix: handle null", cleaner.clean("  fix: handle null  "));
    }
}
