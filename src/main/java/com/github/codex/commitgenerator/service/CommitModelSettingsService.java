package com.github.codex.commitgenerator.service;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Stores the OpenCode model selected for commit generation in IDEA's application settings.
 * The service is shared by the Settings page and commit action, and never reads or writes
 * Codemoss configuration files. Its state lifetime is managed by the IDEA application.
 */
@State(name = "IdeaCommitGeneratorModelSettings", storages = @Storage("idea-commit-generator.xml"))
public final class CommitModelSettingsService implements PersistentStateComponent<CommitModelSettingsService.State> {

    /** OpenCode Go model ID used for the initial and invalid-state fallback selection. */
    public static final String LONGCAT_MODEL = "opencode-go/longcat-2.5-free";

    /** OpenCode Go model ID exposed as the alternative fixed selection. */
    public static final String DEEPSEEK_MODEL = "opencode-go/deepseek-v4.1-flash";

    /** Fixed choices shared by the Settings UI and state validation. */
    private static final List<ModelOption> MODEL_OPTIONS = List.of(
            new ModelOption("LongCat 2.5 Free", LONGCAT_MODEL),
            new ModelOption("DeepSeek V4.1 Flash", DEEPSEEK_MODEL)
    );

    /** Persisted application state loaded and saved by IDEA's component store. */
    private State state = new State();

    /** Returns the application service used by the action and Settings page. */
    @NotNull
    public static CommitModelSettingsService getInstance() {
        return ApplicationManager.getApplication().getService(CommitModelSettingsService.class);
    }

    /**
     * Returns the saved model ID, normalizing missing or obsolete persisted values to LongCat.
     * This protects generation from malformed state left by an older plugin version.
     *
     * @return explicit OpenCode model ID
     */
    @NotNull
    public String getSelectedModelId() {
        return isSupportedModel(state.modelId) ? state.modelId : LONGCAT_MODEL;
    }

    /**
     * Saves a model ID selected by the Settings page.
     * Unsupported values are replaced with the fixed default so the CLI never receives an unknown entry.
     *
     * @param modelId model ID from the fixed Settings choices
     */
    public void setSelectedModelId(@Nullable String modelId) {
        state.modelId = isSupportedModel(modelId) ? modelId : LONGCAT_MODEL;
    }

    /** Returns the fixed choices shown by the Settings combo box. */
    @NotNull
    public static List<ModelOption> getModelOptions() {
        return MODEL_OPTIONS;
    }

    @Override
    @NotNull
    public State getState() {
        // IDEA serializes this small state object into the plugin's private application settings file.
        return state;
    }

    @Override
    public void loadState(@NotNull State loadedState) {
        // Normalize on load so deleted or renamed choices cannot break a later commit generation.
        state = new State();
        setSelectedModelId(loadedState.modelId);
    }

    private static boolean isSupportedModel(@Nullable String modelId) {
        if (modelId == null) return false;
        return MODEL_OPTIONS.stream().anyMatch(option -> option.modelId().equals(modelId));
    }

    /** Display label and exact OpenCode model ID for one Settings choice. */
    public record ModelOption(@NotNull String displayName, @NotNull String modelId) {
        @Override
        public String toString() {
            return displayName;
        }
    }

    /** Persistent application state; the field name is intentionally stable across plugin restarts. */
    public static final class State {
        /** Exact OpenCode model ID selected in IDEA Settings. */
        public String modelId = LONGCAT_MODEL;
    }
}
