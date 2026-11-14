package com.github.codex.commitgenerator.service;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangesUtil;
import com.intellij.openapi.vcs.changes.ContentRevision;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;

/**
 * Resolves the unified diff for the exact Change collection supplied by IDEA's Commit panel.
 * Git4Idea is preferred; a selected-change content fallback keeps Git-less configurations usable.
 */
public final class CommitDiffService {

    private static final Logger LOG = Logger.getInstance(CommitDiffService.class);
    private final Project project;

    public CommitDiffService(@NotNull Project project) {
        this.project = project;
    }

    /**
     * Produces a diff for selected changes without broadening to the project's full changelist.
     *
     * @param changes selected changes from the Commit workflow
     * @return unified diff or an empty string when selected revisions cannot be read
     */
    @NotNull
    public String generate(@NotNull Collection<Change> changes) {
        // Git4Idea backend is an application service registered by the optional feature descriptor.
        GitDiffBackend backend = ApplicationManager.getApplication().getService(GitDiffBackend.class);
        if (backend != null) {
            String gitDiff = backend.diff(project, changes);
            if (!gitDiff.isBlank()) return gitDiff;
        }

        // Fallback is still bounded by the selected collection and never asks ChangeListManager for all files.
        StringBuilder output = new StringBuilder();
        for (Change change : changes) {
            try {
                appendChange(output, change);
            } catch (VcsException exception) {
                LOG.warn("Could not read selected change: " + exception.getMessage());
            }
        }
        return output.toString();
    }

    private void appendChange(@NotNull StringBuilder output, @NotNull Change change) throws VcsException {
        FilePath filePath = ChangesUtil.getFilePath(change);
        String path = filePath == null ? "(unknown)" : filePath.getPath().replace('\\', '/');
        ContentRevision before = change.getBeforeRevision();
        ContentRevision after = change.getAfterRevision();
        String beforeText = before == null ? "" : safeContent(before.getContent());
        String afterText = after == null ? "" : safeContent(after.getContent());

        output.append("diff --git a/").append(path).append(" b/").append(path).append('\n');
        if (change.getType() == Change.Type.NEW) {
            output.append("new file mode 100644\n--- /dev/null\n+++ b/").append(path).append('\n');
        } else if (change.getType() == Change.Type.DELETED) {
            output.append("deleted file mode 100644\n--- a/").append(path).append("\n+++ /dev/null\n");
        } else {
            output.append("--- a/").append(path).append("\n+++ b/").append(path).append('\n');
        }
        output.append("@@ content fallback @@\n");
        for (String line : beforeText.split("\\R", -1)) {
            if (!line.isEmpty()) output.append('-').append(line).append('\n');
        }
        for (String line : afterText.split("\\R", -1)) {
            if (!line.isEmpty()) output.append('+').append(line).append('\n');
        }
    }

    @NotNull
    private String safeContent(String content) {
        return content == null ? "" : content.replace("\r\n", "\n").replace('\r', '\n');
    }
}
