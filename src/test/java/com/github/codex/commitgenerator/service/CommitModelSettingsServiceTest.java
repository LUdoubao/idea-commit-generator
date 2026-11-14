package com.github.codex.commitgenerator.service;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Verifies fixed model choices, defaulting, and persistence-state normalization. */
public class CommitModelSettingsServiceTest {

    @Test
    public void defaultsToLongCatAndExposesConfirmedChoices() {
        CommitModelSettingsService service = new CommitModelSettingsService();

        assertEquals(CommitModelSettingsService.LONGCAT_MODEL, service.getSelectedModelId());
        assertEquals(2, CommitModelSettingsService.getModelOptions().size());
        assertEquals("LongCat 2.5 Free", CommitModelSettingsService.getModelOptions().get(0).displayName());
        assertEquals("DeepSeek V4.1 Flash", CommitModelSettingsService.getModelOptions().get(1).displayName());
    }

    @Test
    public void savesDeepSeekAndRejectsUnknownPersistedValues() {
        CommitModelSettingsService service = new CommitModelSettingsService();

        service.setSelectedModelId(CommitModelSettingsService.DEEPSEEK_MODEL);
        assertEquals(CommitModelSettingsService.DEEPSEEK_MODEL, service.getSelectedModelId());

        service.setSelectedModelId("legacy-or-unknown-model");
        assertEquals(CommitModelSettingsService.LONGCAT_MODEL, service.getSelectedModelId());
    }

    @Test
    public void loadStateRestoresSupportedModelAndFallsBackForInvalidState() {
        CommitModelSettingsService service = new CommitModelSettingsService();
        CommitModelSettingsService.State saved = new CommitModelSettingsService.State();
        saved.modelId = CommitModelSettingsService.DEEPSEEK_MODEL;

        service.loadState(saved);
        assertEquals(CommitModelSettingsService.DEEPSEEK_MODEL, service.getSelectedModelId());

        saved.modelId = "";
        service.loadState(saved);
        assertEquals(CommitModelSettingsService.LONGCAT_MODEL, service.getSelectedModelId());
        assertFalse(service.getSelectedModelId().isBlank());
        assertTrue(CommitModelSettingsService.getModelOptions().stream()
                .anyMatch(option -> option.modelId().equals(service.getSelectedModelId())));
    }
}
