package com.github.codex.commitgenerator.service;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.changes.Change;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;

/**
 * Produces a unified diff for exactly the changes included in one IDEA commit request.
 * Implementations are registered only when Git4Idea is available and run on a pooled thread.
 */
public interface GitDiffBackend {

    /**
     * Reads the selected changes without changing the repository or its index.
     *
     * @param project owning IDEA project
     * @param changes selected commit changes
     * @return unified diff text, or an empty string when no diff can be produced
     */
    @NotNull
    String diff(@NotNull Project project, @NotNull Collection<Change> changes);
}
