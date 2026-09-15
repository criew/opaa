package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.github.dockerjava.api.command.CreateContainerCmd;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Docker-free guard for the CPU backend pin of the eval Ollama container (issue #1652): without it,
 * the same model digest computes with a different ggml kernel set on a host with AVX-512 than on
 * one without, and both greedy decomposition and embedding rankings diverge between runners.
 */
class EvalOllamaCpuBackendTest {

  private static final String HASWELL_RUNNER_LOG =
      """
      time=2026-09-15T06:58:43.116Z level=INFO source=server.go:405 msg="starting llama server"
      load_backend: loaded CPU backend from /usr/lib/ollama/libggml-cpu-haswell.so
      llama_model_loader: loaded meta data with 34 key-value pairs
      """;

  private static final String ICELAKE_RUNNER_LOG =
      "load_backend: loaded CPU backend from /usr/lib/ollama/libggml-cpu-icelake.so\n";

  private static final List<String> VARIANTS =
      List.of("alderlake", "haswell", "icelake", "sandybridge", "skylakex");

  @Test
  void thePinnedVariantIsTheAvx2KernelSetEveryX86HostCanExecute() {
    assertThat(EvalOllamaCpuBackend.PINNED).isEqualTo("haswell");
  }

  @Test
  void theContainerRunsTheStartScriptThroughAShell() {
    CreateContainerCmd cmd = mock(CreateContainerCmd.class, RETURNS_SELF);

    EvalOllamaCpuBackend.pin(cmd);

    verify(cmd).withEntrypoint("/bin/sh", "-c");
    verify(cmd)
        .withCmd(
            EvalOllamaCpuBackend.startScript(
                EvalOllamaCpuBackend.LIBRARY_DIR, "/bin/ollama serve"));
  }

  @Test
  void theStartScriptKeepsOnlyThePinnedVariantAndThenStartsTheServer(@TempDir Path libraryDir)
      throws Exception {
    for (String variant : VARIANTS) {
      Files.writeString(libraryDir.resolve("libggml-cpu-" + variant + ".so"), "");
    }
    Files.writeString(libraryDir.resolve("libggml-base.so"), "");

    ShellResult result = runStartScript(libraryDir);

    assertThat(result.exitCode()).isZero();
    assertThat(result.output()).contains("server started");
    try (Stream<Path> files = Files.list(libraryDir)) {
      assertThat(files.map(file -> file.getFileName().toString()))
          .containsExactlyInAnyOrder("libggml-cpu-haswell.so", "libggml-base.so");
    }
  }

  @Test
  void theStartScriptRefusesToStartWithoutThePinnedVariant(@TempDir Path libraryDir)
      throws Exception {
    Files.writeString(libraryDir.resolve("libggml-cpu-icelake.so"), "");

    ShellResult result = runStartScript(libraryDir);

    assertThat(result.exitCode()).isNotZero();
    assertThat(result.output())
        .contains("pinned ggml CPU backend missing")
        .doesNotContain("server started");
    assertThat(libraryDir.resolve("libggml-cpu-icelake.so")).exists();
  }

  @Test
  void everyRunnerThatLoadedTheBackendIsReadFromTheContainerLog() {
    assertThat(EvalOllamaCpuBackend.loadedBackends(HASWELL_RUNNER_LOG + ICELAKE_RUNNER_LOG))
        .containsExactly("haswell", "icelake");
    assertThat(EvalOllamaCpuBackend.loadedBackends("")).isEmpty();
  }

  @Test
  void aRunWhoseRunnersComputedWithThePinnedVariantPasses() {
    assertThat(
            EvalOllamaCpuBackend.requirePinnedBackendLoaded(
                HASWELL_RUNNER_LOG + HASWELL_RUNNER_LOG, 2))
        .isEqualTo(EvalOllamaCpuBackend.PINNED);
  }

  /** The state of every run before the pin on an AVX-512 host: ggml picks its best variant. */
  @Test
  void aRunThatComputedWithAnotherVariantIsRefused() {
    assertThatThrownBy(
            () ->
                EvalOllamaCpuBackend.requirePinnedBackendLoaded(
                    HASWELL_RUNNER_LOG + ICELAKE_RUNNER_LOG, 1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("icelake")
        .hasMessageContaining("haswell");
  }

  /** A decomposing run whose chat runner never reported proves nothing about the chat kernels. */
  @Test
  void aRunWithFewerLoadedRunnersThanExpectedIsRefused() {
    assertThatThrownBy(() -> EvalOllamaCpuBackend.requirePinnedBackendLoaded(HASWELL_RUNNER_LOG, 2))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("expected at least 2");
    assertThatThrownBy(() -> EvalOllamaCpuBackend.requirePinnedBackendLoaded("starting\n", 1))
        .isInstanceOf(IllegalStateException.class);
  }

  private record ShellResult(int exitCode, String output) {}

  private static ShellResult runStartScript(Path libraryDir)
      throws IOException, InterruptedException {
    String script =
        EvalOllamaCpuBackend.startScript(
            libraryDir.toAbsolutePath().toString().replace('\\', '/'), "echo server started");
    Process process;
    try {
      process = new ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start();
    } catch (IOException e) {
      assumeTrue(false, "no POSIX sh on this machine: " + e.getMessage());
      throw e;
    }
    assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    return new ShellResult(process.exitValue(), output);
  }
}
