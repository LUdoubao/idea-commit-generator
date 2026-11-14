package com.github.codex.commitgenerator.service;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangesUtil;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vfs.VirtualFile;
import git4idea.commands.Git;
import git4idea.commands.GitCommand;
import git4idea.commands.GitCommandResult;
import git4idea.commands.GitLineHandler;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds commit diffs with Git4Idea while preserving the exact included-change boundary.
 * The service is registered only from the optional Git4Idea feature descriptor.
 */
public final class Git4IdeaDiffBackend implements GitDiffBackend {

    private static final Logger LOG = Logger.getInstance(Git4IdeaDiffBackend.class);

    /**
     * Builds a diff for only the selected paths, falling back to selected revisions when Git cannot resolve one.
     *
     * @param project current IDEA project
     * @param changes included Commit panel changes
     * @return unified diff text for the included changes
     */
    @Override
    @NotNull
    public String diff(@NotNull Project project, @NotNull Collection<Change> changes) {
        // Keep repository groups separate so each git command is rooted in the correct worktree.
        GitRepositoryManager manager = GitRepositoryManager.getInstance(project);
        Map<GitRepository, List<String>> trackedPaths = new LinkedHashMap<>();
        List<Change> newFiles = new ArrayList<>();
        List<Change> contentFallbacks = new ArrayList<>();

        // Resolve only paths represented by IDEA's included Change collection; never broaden to all changes.
        for (Change change : changes) {
            FilePath filePath = ChangesUtil.getFilePath(change);
            if (change.getType() == Change.Type.NEW) {
                newFiles.add(change);
                continue;
            }
            GitRepository repository = findRepository(manager, filePath);
            String relativePath = relativePath(repository, filePath);
            if (repository == null || relativePath == null) {
                contentFallbacks.add(change);
                continue;
            }
            trackedPaths.computeIfAbsent(repository, ignored -> new ArrayList<>()).add(relativePath);
        }

        StringBuilder result = new StringBuilder();
        // Ask git for tracked selected paths only; the explicit pathspec prevents whole-repository diffs.
        for (Map.Entry<GitRepository, List<String>> entry : trackedPaths.entrySet()) {
            String gitDiff = runSelectedPathDiff(project, entry.getKey(), entry.getValue());
            if (!gitDiff.isEmpty()) {
                result.append(gitDiff).append('\n');
                continue;
            }
            // Revision content remains a safe fallback when git4idea cannot resolve a selected path.
            for (Change change : changes) {
                FilePath path = ChangesUtil.getFilePath(change);
                if (path != null && entry.getValue().contains(relativePath(entry.getKey(), path))) {
                    appendContentFallback(result, change);
                }
            }
        }

        // Git does not include untracked paths in `git diff HEAD`; synthesize their selected contents.
        for (Change change : newFiles) {
            appendNewFile(result, change);
        }
        // Content revisions cover only selected files whose repository could not be resolved.
        for (Change change : contentFallbacks) {
            appendContentFallback(result, change);
        }
        return result.toString();
    }

    /**
     * Runs one path-limited diff for a repository group; failure returns empty for content fallback.
     *
     * @param project current project required by Git4Idea's command handler
     * @param repository selected file repository
     * @param paths repository-relative selected paths
     * @return Git's unified diff, or empty on command failure/no tracked content
     */
    @NotNull
    private String runSelectedPathDiff(@NotNull Project project,
                                       @NotNull GitRepository repository,
                                       @NotNull List<String> paths) {
        if (paths.isEmpty()) {
            return "";
        }
        try {
            // `--` separates pathspecs from options; only selected paths cross this boundary.
            GitLineHandler handler = new GitLineHandler(project, repository.getRoot(), GitCommand.DIFF);
            handler.addParameters("--unified=3", "--no-color", "-M", "--no-ext-diff", "HEAD");
            handler.endOptions();
            handler.addParameters("--");
            paths.forEach(handler::addParameters);
            GitCommandResult commandResult = Git.getInstance().runCommand(handler);
            return commandResult.success() ? String.join("\n", commandResult.getOutput()) : "";
        } catch (RuntimeException exception) {
            LOG.warn("Selected-path git diff failed: " + exception.getMessage());
            return "";
        }
    }

    @Nullable
    private GitRepository findRepository(@NotNull GitRepositoryManager manager, @Nullable FilePath filePath) {
        if (filePath == null) {
            return null;
        }
        // Prefer IDEA's virtual-file index, then check repository roots for deleted files.
        VirtualFile virtualFile = filePath.getVirtualFile();
        if (virtualFile != null) {
            GitRepository repository = manager.getRepositoryForFile(virtualFile);
            if (repository != null) {
                return repository;
            }
        }
        String absolutePath = filePath.getPath();
        for (GitRepository repository : manager.getRepositories()) {
            if (relativePath(repository, filePath) != null) {
                return repository;
            }
        }
        LOG.debug("No Git repository contains selected path: " + absolutePath);
        return null;
    }

    @Nullable
    private String relativePath(@Nullable GitRepository repository, @Nullable FilePath filePath) {
        if (repository == null || filePath == null) {
            return null;
        }
        try {
            // Path.startsWith compares path segments, avoiding false prefix matches between sibling roots.
            Path root = Paths.get(repository.getRoot().getPath()).toAbsolutePath().normalize();
            Path absolute = Paths.get(filePath.getPath()).toAbsolutePath().normalize();
            if (!absolute.startsWith(root)) {
                return null;
            }
            return root.relativize(absolute).toString().replace('\\', '/');
        } catch (RuntimeException exception) {
            LOG.debug("Could not relativize selected path: " + exception.getMessage());
            return null;
        }
    }

    private void appendNewFile(@NotNull StringBuilder target, @NotNull Change change) {
        FilePath filePath = ChangesUtil.getFilePath(change);
        ContentRevision after = change.getAfterRevision();
        try {
            String content = after == null ? null : after.getContent();
            if (content == null) {
                return;
            }
            String path = filePath == null ? "(unknown)" : filePath.getPath().replace('\\', '/');
            String[] lines = content.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
            target.append("diff --git a/").append(path).append(" b/").append(path).append('\n')
                    .append("new file mode 100644\n--- /dev/null\n+++").append(" b/").append(path).append('\n')
                    .append("@@ -0,0 +1,").append(lines.length).append(" @@\n");
            // Include the full selected new-file content; prompt transport uses stdin, not argv.
            for (String line : lines) {
                target.append('+').append(line).append('\n');
            }
        } catch (VcsException exception) {
            LOG.warn("Could not read selected new file: " + exception.getMessage());
        }
    }

    private void appendContentFallback(@NotNull StringBuilder target, @NotNull Change change) {
        try {
            FilePath filePath = ChangesUtil.getFilePath(change);
            ContentRevision before = change.getBeforeRevision();
            ContentRevision after = change.getAfterRevision();
            String oldText = before == null ? "" : before.getContent();
            String newText = after == null ? "" : after.getContent();
            if (oldText == null) oldText = "";
            if (newText == null) newText = "";
            String name = filePath == null ? "(unknown)" : filePath.getPath().replace('\\', '/');
            String[] oldLines = oldText.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
            String[] newLines = newText.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
            // Fallback is limited to the selected file and marks full old/new contents explicitly.
            target.append("diff --git a/").append(name).append(" b/").append(name).append('\n')
                    .append("--- ").append(before == null ? "/dev/null" : "a/" + name).append('\n')
                    .append("+++ ").append(after == null ? "/dev/null" : "b/" + name).append('\n')
                    .append("@@ content fallback @@\n");
            for (String line : oldLines) target.append('-').append(line).append('\n');
            for (String line : newLines) target.append('+').append(line).append('\n');
        } catch (VcsException exception) {
            LOG.warn("Could not read selected change content: " + exception.getMessage());
        }
    }
}
