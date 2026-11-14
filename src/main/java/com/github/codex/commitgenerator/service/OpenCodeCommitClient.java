package com.github.codex.commitgenerator.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs OpenCode's one-shot JSON CLI for a single commit prompt.
 * Each invocation owns one process and one stdin pipe; cancellation destroys the full process tree.
 */
public final class OpenCodeCommitClient {

    private static final Logger LOG = Logger.getInstance(OpenCodeCommitClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(90);
    private static final int STDERR_TAIL_CHARS = 800;

    private volatile Process currentProcess;

    /**
     * Sends a complete prompt over UTF-8 stdin and returns concatenated OpenCode text events.
     *
     * @param prompt complete fixed-policy prompt plus selected Git diff
     * @param model explicit model ID selected in IDEA Settings
     * @return raw assistant response for commit-tag cleanup
     * @throws OpenCodeException when startup, transport, timeout, parsing, or generation fails
     */
    @NotNull
    public String generate(@NotNull String prompt, @NotNull String model) throws OpenCodeException {
        ResolvedCommand resolved;
        try {
            resolved = resolveCommand(model);
        } catch (IOException exception) {
            throw new OpenCodeException(exception.getMessage(), exception);
        }

        Process process = null;
        CompletableFuture<String> stdoutFuture = null;
        CompletableFuture<String> stderrFuture = null;
        try {
            // Readers start before stdin is written so a verbose CLI cannot block on a full output pipe.
            ProcessBuilder processBuilder = new ProcessBuilder(resolved.command())
                    .directory(resolved.workingDirectory().toFile())
                    .redirectErrorStream(false)
                    ;
            if (resolved.binaryDirectory() != null) {
                // Explicit npm shims are invoked by basename; add only their directory to this child PATH.
                String currentPath = processBuilder.environment().getOrDefault("PATH",
                        processBuilder.environment().getOrDefault("Path", ""));
                processBuilder.environment().put("PATH", resolved.binaryDirectory() + File.pathSeparator + currentPath);
            }
            process = processBuilder.start();
            currentProcess = process;
            Process activeProcess = process;
            stdoutFuture = CompletableFuture.supplyAsync(() -> readUtf8(activeProcess.getInputStream()));
            stderrFuture = CompletableFuture.supplyAsync(() -> readTail(activeProcess.getErrorStream()));

            // The prompt never enters argv; stdin avoids Windows' CreateProcess command-line limit.
            try (var input = process.getOutputStream()) {
                input.write(prompt.getBytes(StandardCharsets.UTF_8));
                input.flush();
            }

            if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                terminateProcessTree(process);
                throw new OpenCodeException("OpenCode generation timed out after " + TIMEOUT.toSeconds() + " seconds");
            }
            String stdout = await(stdoutFuture, "OpenCode stdout");
            String stderr = await(stderrFuture, "OpenCode stderr");
            ParsedOutput parsed = parseOutput(stdout);
            if (!parsed.error().isEmpty()) {
                throw new OpenCodeException(parsed.error());
            }
            if (process.exitValue() != 0) {
                String detail = stderr.isBlank() ? "" : ": " + stderr;
                throw new OpenCodeException("OpenCode exited with code " + process.exitValue() + detail);
            }
            if (parsed.text().isBlank()) {
                throw new OpenCodeException("OpenCode returned no assistant text"
                        + (stderr.isBlank() ? "" : ": " + stderr));
            }
            return parsed.text();
        } catch (OpenCodeException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            if (process != null) terminateProcessTree(process);
            Thread.currentThread().interrupt();
            throw new OpenCodeException("OpenCode generation was interrupted", exception);
        } catch (IOException exception) {
            if (process != null) terminateProcessTree(process);
            throw new OpenCodeException("Could not run OpenCode: " + exception.getMessage(), exception);
        } finally {
            // Clear the handle only if it still points at this run, protecting a later replacement run.
            if (currentProcess == process) currentProcess = null;
            if (process != null && process.isAlive()) terminateProcessTree(process);
        }
    }

    /** Cancels the current OpenCode process and all children started by npm/cmd shims. */
    public void cancel() {
        Process process = currentProcess;
        if (process != null) terminateProcessTree(process);
    }

    /**
     * Resolves a supported executable using explicit environment overrides before PATH lookup.
     *
     * @param model OpenCode model id
     * @return command tokens and a valid working directory
     * @throws IOException if an explicit binary is invalid or OpenCode cannot be found
     */
    private ResolvedCommand resolveCommand(@NotNull String model) throws IOException {
        Map<String, String> environment = System.getenv();
        String explicit = firstNonBlank(environment.get("OPENCODE_BIN"), environment.get("OPENCODE_PATH"),
                environment.get("OPENCODE_CLI_PATH"));
        Path executable = explicit == null ? findOnPath(environment) : resolveExplicit(explicit);
        if (executable == null) {
            throw new IOException(explicit == null
                    ? "OpenCode was not found. Install it or add opencode.exe/opencode.cmd to PATH."
                    : "Configured OpenCode executable does not exist: " + explicit);
        }

        List<String> arguments = new ArrayList<>();
        boolean isWindows = isWindows();
        if (isWindows && isCommandShim(executable)) {
            // Invoke npm shims by basename; placing the directory first in PATH avoids quoted spaced paths.
            String commandLine = quoteCmd(executable.getFileName().toString())
                    + " " + quoteCmd("run") + " " + quoteCmd("--format") + " " + quoteCmd("json");
            // The Settings page always supplies an explicit model, so the CLI request is auditable and deterministic.
            commandLine += " " + quoteCmd("--model") + " " + quoteCmd(model);
            String comSpec = firstNonBlank(environment.get("ComSpec"), environment.get("COMSPEC"), "cmd.exe");
            arguments.add(comSpec);
            arguments.add("/d");
            arguments.add("/s");
            arguments.add("/c");
            arguments.add('"' + commandLine + '"');
            return new ResolvedCommand(arguments, userHome(), executable.getParent());
        }

        arguments.add(executable.toString());
        arguments.add("run");
        arguments.add("--format");
        arguments.add("json");
        // Keep the selected model in argv while the potentially large prompt remains on stdin.
        arguments.add("--model");
        arguments.add(model);
        return new ResolvedCommand(arguments, userHome(), null);
    }

    @NotNull
    private static Path userHome() {
        // OpenCode resolves user-level config and auth relative to the user's home, not its install directory.
        return Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
    }

    @Nullable
    private Path findOnPath(@NotNull Map<String, String> environment) {
        String pathValue = environment.getOrDefault("PATH", environment.get("Path"));
        if (pathValue == null) return null;
        String[] extensions = isWindows() ? new String[]{".exe", ".cmd", ".bat"} : new String[]{""};
        for (String entry : pathValue.split(java.util.regex.Pattern.quote(System.getProperty("path.separator")))) {
            if (entry.isBlank()) continue;
            Path directory = Path.of(entry.trim());
            for (String extension : extensions) {
                Path candidate = directory.resolve("opencode" + extension);
                if (Files.isRegularFile(candidate)) return candidate.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    @NotNull
    private Path resolveExplicit(@NotNull String configured) {
        Path path = Path.of(configured.trim().replace("\"", "")).toAbsolutePath().normalize();
        if (Files.isRegularFile(path)) return path;
        String fileName = path.getFileName() == null ? "" : path.getFileName().toString();
        if (!fileName.contains(".")) {
            String[] extensions = isWindows() ? new String[]{".exe", ".cmd", ".bat"} : new String[]{""};
            for (String extension : extensions) {
                Path sibling = path.resolveSibling(fileName + extension);
                if (Files.isRegularFile(sibling)) return sibling;
            }
        }
        return path;
    }

    private static boolean isCommandShim(@NotNull Path executable) {
        String name = executable.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".cmd") || name.endsWith(".bat");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** Quotes one cmd.exe token and prevents percent expansion in a configured model id. */
    @NotNull
    private static String quoteCmd(@NotNull String value) {
        return "\"" + value.replace("%", "%%").replace("\"", "\"\"") + "\"";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }

    @NotNull
    private static String readUtf8(@NotNull InputStream stream) {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) output.append(line).append('\n');
        } catch (IOException exception) {
            throw new RuntimeException(exception);
        }
        return output.toString();
    }

    @NotNull
    private static String readTail(@NotNull InputStream stream) {
        StringBuilder tail = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                tail.append(line).append('\n');
                if (tail.length() > STDERR_TAIL_CHARS) {
                    tail.delete(0, tail.length() - STDERR_TAIL_CHARS);
                }
            }
        } catch (IOException exception) {
            LOG.debug("Could not read OpenCode stderr: " + exception.getMessage());
        }
        return tail.toString().trim();
    }

    @NotNull
    private static String await(@NotNull CompletableFuture<String> future, @NotNull String streamName)
            throws InterruptedException, OpenCodeException {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            throw new OpenCodeException(streamName + " did not close after OpenCode exited", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw new OpenCodeException("Could not read " + streamName + ": " + cause.getMessage(), cause);
        }
    }

    private static void terminateProcessTree(@NotNull Process process) {
        // Stop descendants first so a cmd/npm wrapper cannot keep streams open after cancellation.
        process.toHandle().descendants().forEach(handle -> {
            try {
                handle.destroyForcibly();
            } catch (RuntimeException ignored) {
                // Continue terminating the remaining tree even if a child already exited.
            }
        });
        process.destroyForcibly();
    }

    private static ParsedOutput parseOutput(@NotNull String stdout) {
        StringBuilder text = new StringBuilder();
        String structuredError = "";
        for (String line : stdout.split("\\R")) {
            if (line.isBlank()) continue;
            try {
                JsonObject event = JsonParser.parseString(line).getAsJsonObject();
                String type = stringValue(event.get("type")).toLowerCase(Locale.ROOT);
                if (type.equals("error") || type.endsWith(".error")) {
                    structuredError = errorMessage(event);
                    continue;
                }
                if (type.contains("reasoning") || type.contains("think")) continue;
                String chunk = textChunk(event);
                if (!chunk.isEmpty()) text.append(chunk);
            } catch (RuntimeException exception) {
                LOG.debug("Ignoring non-JSON OpenCode stdout line: " + exception.getMessage());
            }
        }
        return new ParsedOutput(text.toString(), structuredError);
    }

    @NotNull
    private static String textChunk(@NotNull JsonObject event) {
        String type = stringValue(event.get("type")).toLowerCase(Locale.ROOT);
        if (!(type.equals("text") || type.contains("delta") || type.contains("message"))) return "";
        JsonElement partValue = event.get("part");
        if (partValue != null && partValue.isJsonObject()) {
            String partText = stringValue(partValue.getAsJsonObject().get("text"));
            if (!partText.isEmpty()) return partText;
        }
        String delta = stringValue(event.get("delta"));
        if (!delta.isEmpty()) return delta;
        return stringValue(event.get("text"));
    }

    @NotNull
    private static String errorMessage(@NotNull JsonObject event) {
        JsonElement errorElement = event.get("error");
        if (errorElement == null || !errorElement.isJsonObject()) return stringValue(errorElement);
        JsonObject error = errorElement.getAsJsonObject();
        String direct = stringValue(error.get("message"));
        if (!direct.isEmpty()) return direct;
        JsonElement data = error.get("data");
        if (data != null && data.isJsonObject()) {
            String nested = stringValue(data.getAsJsonObject().get("message"));
            if (!nested.isEmpty()) return nested;
        }
        String name = stringValue(error.get("name"));
        return name.isEmpty() ? "OpenCode returned an error event" : name;
    }

    @NotNull
    private static String stringValue(@Nullable JsonElement value) {
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private record ResolvedCommand(List<String> command, Path workingDirectory, @Nullable Path binaryDirectory) { }

    private record ParsedOutput(String text, String error) { }

    /** Typed process/protocol failure displayed by the Commit Action. */
    public static final class OpenCodeException extends Exception {
        public OpenCodeException(@NotNull String message) { super(message); }
        public OpenCodeException(@NotNull String message, @NotNull Throwable cause) { super(message, cause); }
    }
}
