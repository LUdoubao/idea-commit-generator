package com.github.codex.commitgenerator.action;

import com.github.codex.commitgenerator.service.CommitDiffService;
import com.github.codex.commitgenerator.service.CommitMessageCleaner;
import com.github.codex.commitgenerator.service.CommitPrompt;
import com.github.codex.commitgenerator.service.CommitModelSettingsService;
import com.github.codex.commitgenerator.service.OpenCodeCommitClient;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.CommitMessageI;
import com.intellij.openapi.vcs.VcsDataKeys;
import com.intellij.openapi.vcs.changes.Change;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;

/**
 * IDEA VCS Commit action for generating a message from the Commit panel's included changes.
 * The action owns UI state; diff, configuration, process, and output work live in services.
 */
public final class CommitGeneratorAction extends AnAction implements DumbAware {

    private static final Logger LOG = Logger.getInstance(CommitGeneratorAction.class);
    private static final String TITLE = "IDEA Commit Generator";
    private static final Map<Project, Generation> ACTIVE = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Runs presentation updates in the background because the action only reads lightweight VCS context.
     *
     * @return background action update mode
     */
    @Override
    @NotNull
    public ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    /**
     * Starts one asynchronous generation for the Commit panel's included changes.
     *
     * @param event IDEA action event containing project, workflow, panel, and included changes
     */
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        CommitMessageI panel = getCommitMessagePanel(event);
        List<Change> changes = getIncludedChanges(event);
        if (project == null || panel == null) {
            notifyUser(project, "无法访问 IDEA Commit 面板", NotificationType.WARNING);
            return;
        }
        if (changes.isEmpty()) {
            notifyUser(project, "请先在 Commit 面板中选择要提交的变更", NotificationType.WARNING);
            return;
        }

        cancelActive(project);
        String savedDraft = readDraft(panel);
        Generation generation = new Generation(project, panel, savedDraft);
        ACTIVE.put(project, generation);
        panel.setCommitMessage("正在生成 Commit Message...");

        // Git and OpenCode work must stay off the EDT so the Commit dialog remains responsive.
        CompletableFuture.runAsync(() -> generate(generation, changes));
    }

    private void generate(@NotNull Generation generation, @NotNull List<Change> changes) {
        try {
            if (generation.cancelled().get()) return;
            CommitDiffService diffService = new CommitDiffService(generation.project());
            String diff = diffService.generate(changes);
            if (diff.isBlank()) throw new IllegalStateException("没有获取到选中变更的 diff");

            // A replacement click during diff collection must not start a stale paid model request.
            if (generation.cancelled().get()) return;
            String prompt = new CommitPrompt().build(diff);
            // Read the application setting after diff collection so the request uses the model currently saved in IDEA.
            String model = ApplicationManager.getApplication().getService(CommitModelSettingsService.class)
                    .getSelectedModelId();
            if (generation.cancelled().get()) return;
            String raw = generation.client().generate(prompt, model);
            if (generation.cancelled().get()) return;
            String message = new CommitMessageCleaner().clean(raw);
            if (message.isBlank()) throw new IllegalStateException("OpenCode 未返回有效 Commit Message");
            updateSuccess(generation, message);
        } catch (Exception exception) {
            LOG.warn("Commit generation failed: " + exception.getMessage(), exception);
            updateFailure(generation, exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
        }
    }

    private void updateSuccess(@NotNull Generation generation, @NotNull String message) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (isActive(generation)) {
                ACTIVE.remove(generation.project());
                generation.panel().setCommitMessage(message);
                notifyUser(generation.project(), "Commit Message 生成成功", NotificationType.INFORMATION);
            }
        }, ModalityState.any());
    }

    private void updateFailure(@NotNull Generation generation, @NotNull String error) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (isActive(generation)) {
                ACTIVE.remove(generation.project());
                // Restore the user's draft so a failed generation cannot destroy manual work.
                generation.panel().setCommitMessage(generation.savedDraft());
                notifyUser(generation.project(), "Commit Message 生成失败：" + error, NotificationType.ERROR);
            }
        }, ModalityState.any());
    }

    private void cancelActive(@NotNull Project project) {
        Generation previous = ACTIVE.remove(project);
        if (previous != null) {
            previous.cancelled().set(true);
            previous.client().cancel();
        }
    }

    private boolean isActive(@NotNull Generation generation) {
        return ACTIVE.get(generation.project()) == generation;
    }

    @Nullable
    private CommitMessageI getCommitMessagePanel(@NotNull AnActionEvent event) {
        Object workflow = event.getData(VcsDataKeys.COMMIT_WORKFLOW_HANDLER);
        if (workflow instanceof CommitMessageI message) return message;
        return event.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL);
    }

    @NotNull
    private List<Change> getIncludedChanges(@NotNull AnActionEvent event) {
        Object workflow = event.getData(VcsDataKeys.COMMIT_WORKFLOW_HANDLER);
        if (workflow != null) {
            try {
                Method getUi = workflow.getClass().getMethod("getUi");
                Object ui = getUi.invoke(workflow);
                if (ui != null) {
                    Method getIncluded = ui.getClass().getMethod("getIncludedChanges");
                    Object included = getIncluded.invoke(ui);
                    if (included instanceof Collection<?> collection) return castChanges(collection);
                }
            } catch (ReflectiveOperationException exception) {
                LOG.debug("Included Changes API unavailable: " + exception.getMessage());
            }
        }
        Change[] changes = event.getData(VcsDataKeys.CHANGES);
        return changes == null ? List.of() : new ArrayList<>(List.of(changes));
    }

    @NotNull
    private List<Change> castChanges(@NotNull Collection<?> values) {
        List<Change> changes = new ArrayList<>();
        for (Object value : values) if (value instanceof Change change) changes.add(change);
        return changes;
    }

    @NotNull
    private String readDraft(@NotNull CommitMessageI panel) {
        try {
            Method getter = panel.getClass().getMethod("getCommitMessage");
            Object value = getter.invoke(panel);
            return value instanceof String ? (String) value : "";
        } catch (ReflectiveOperationException exception) {
            LOG.debug("Could not read current Commit draft: " + exception.getMessage());
            return "";
        }
    }

    private void notifyUser(@Nullable Project project, @NotNull String message, @NotNull NotificationType type) {
        Notification notification = new Notification(TITLE, TITLE, message, type);
        Notifications.Bus.notify(notification, project);
    }

    private record Generation(Project project, CommitMessageI panel, String savedDraft,
                              OpenCodeCommitClient client, AtomicBoolean cancelled) {
        private Generation(Project project, CommitMessageI panel, String savedDraft) {
            this(project, panel, savedDraft, new OpenCodeCommitClient(), new AtomicBoolean());
        }
    }
}
