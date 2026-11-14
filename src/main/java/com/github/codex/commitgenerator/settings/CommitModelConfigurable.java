package com.github.codex.commitgenerator.settings;

import com.github.codex.commitgenerator.service.CommitModelSettingsService;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.components.JBLabel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;

/**
 * IDEA application Settings page for selecting the OpenCode model used by commit generation.
 * The page owns only transient UI state; the application service persists values when Apply runs.
 */
public final class CommitModelConfigurable implements Configurable {

    /** Lazily-created Settings page root, owned by IDEA's configurable lifecycle. */
    private JPanel rootPanel;

    /** Transient selection control; its selected ID is persisted only during Apply. */
    private ComboBox<CommitModelSettingsService.ModelOption> modelComboBox;

    @Override
    @NotNull
    public String getDisplayName() {
        return "IDEA Commit Generator";
    }

    /**
     * Creates the model selector and synchronizes it with the persisted application setting.
     *
     * @return Settings component, or {@code null} only before IDEA requests a page component
     */
    @Override
    @Nullable
    public JComponent createComponent() {
        if (rootPanel == null) {
            // Keep the page intentionally small because the only configurable behavior is model selection.
            rootPanel = new JPanel(new BorderLayout(0, 8));
            rootPanel.add(new JBLabel("Commit 生成模型"), BorderLayout.NORTH);
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            row.add(new JBLabel("模型："));
            modelComboBox = new ComboBox<>();
            for (CommitModelSettingsService.ModelOption option : CommitModelSettingsService.getModelOptions()) {
                modelComboBox.addItem(option);
            }
            row.add(modelComboBox);
            rootPanel.add(row, BorderLayout.CENTER);
        }
        reset();
        return rootPanel;
    }

    /**
     * Reports whether the user has selected a model different from the saved application value.
     *
     * @return {@code true} when Apply should persist a new model
     */
    @Override
    public boolean isModified() {
        if (modelComboBox == null) return false;
        CommitModelSettingsService.ModelOption selected = (CommitModelSettingsService.ModelOption)
                modelComboBox.getSelectedItem();
        return selected != null
                && !selected.modelId().equals(CommitModelSettingsService.getInstance().getSelectedModelId());
    }

    /** Applies the transient combo-box selection to the application settings service. */
    @Override
    public void apply() {
        if (modelComboBox == null) return;
        CommitModelSettingsService.ModelOption selected = (CommitModelSettingsService.ModelOption)
                modelComboBox.getSelectedItem();
        if (selected != null) {
            // Persist only a fixed model ID; the display label is presentation and is never serialized.
            CommitModelSettingsService.getInstance().setSelectedModelId(selected.modelId());
        }
    }

    /** Restores the combo box to the model currently persisted by IDEA. */
    @Override
    public void reset() {
        if (modelComboBox == null) return;
        String selectedModelId = CommitModelSettingsService.getInstance().getSelectedModelId();
        for (int index = 0; index < modelComboBox.getItemCount(); index++) {
            CommitModelSettingsService.ModelOption option = modelComboBox.getItemAt(index);
            if (option.modelId().equals(selectedModelId)) {
                modelComboBox.setSelectedIndex(index);
                return;
            }
        }
        // The service already normalizes invalid state; this is a defensive UI fallback.
        modelComboBox.setSelectedIndex(0);
    }

    /** Releases Swing controls when IDEA closes the Settings page. */
    @Override
    public void disposeUIResources() {
        modelComboBox = null;
        rootPanel = null;
    }
}
