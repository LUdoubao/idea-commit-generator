package com.github.codex.commitgenerator.service;

import org.jetbrains.annotations.NotNull;

/**
 * Extracts a user-ready commit message from OpenCode's text response.
 * The tag contract is preferred; conservative text and code-fence fallbacks support older models.
 */
public final class CommitMessageCleaner {

    private static final String OPEN_TAG = "<commit>";
    private static final String CLOSE_TAG = "</commit>";

    /** Returns cleaned message content, or empty when the model emitted no usable text. */
    @NotNull
    public String clean(@NotNull String raw) {
        String text = raw.trim();
        if (text.isEmpty()) return "";

        // Honor the explicit prompt contract and discard any material outside its first complete tag pair.
        int start = text.indexOf(OPEN_TAG);
        int end = start < 0 ? -1 : text.indexOf(CLOSE_TAG, start + OPEN_TAG.length());
        if (start >= 0 && end > start) {
            return text.substring(start + OPEN_TAG.length(), end).trim();
        }

        // Some compatible models wrap a plain answer in Markdown despite the output-only instruction.
        int fenceStart = text.indexOf("```");
        if (fenceStart >= 0) {
            int bodyStart = text.indexOf('\n', fenceStart);
            int fenceEnd = bodyStart < 0 ? -1 : text.indexOf("```", bodyStart + 1);
            if (fenceEnd > bodyStart) {
                text = text.substring(bodyStart + 1, fenceEnd).trim();
            }
        }
        return text;
    }
}
