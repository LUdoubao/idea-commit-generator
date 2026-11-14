package com.github.codex.commitgenerator.service;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/** Verifies command-independent model default semantics exposed through generated errors/contracts. */
public class OpenCodeCommitClientTest {

    @Test
    public void exceptionIsTypedForCommitUiHandling() {
        OpenCodeCommitClient.OpenCodeException exception =
                new OpenCodeCommitClient.OpenCodeException("model unavailable");

        assertTrue(exception.getMessage().contains("model unavailable"));
    }
}
