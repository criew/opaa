package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.github.dockerjava.api.command.CreateContainerCmd;
import org.junit.jupiter.api.Test;

/**
 * Docker-free guard for the CPU backend pin of the eval Ollama container (issue #1652): without it,
 * the same model digest computes with a different ggml kernel set on a runner with AVX-512 than on
 * one without, and the greedy decomposition of the multi-turn path diverges from the first turn on.
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

  @Test
  void thePinnedVariantIsTheAvx2KernelSetEveryX86RunnerCanExecute() {
    assertThat(EvalOllamaCpuBackend.PINNED).isEqualTo("haswell");
  }

  @Test
  void theContainerStartsTheServerOnlyAfterRemovingEveryOtherCpuVariant() {
    CreateContainerCmd cmd = mock(CreateContainerCmd.class, RETURNS_SELF);

    EvalOllamaCpuBackend.pin(cmd);

    verify(cmd).withEntrypoint("/bin/sh", "-c");
    verify(cmd).withCmd(EvalOllamaCpuBackend.startScript());
    assertThat(EvalOllamaCpuBackend.startScript())
        .contains("/usr/lib/ollama/libggml-cpu-*.so")
        .contains("/usr/lib/ollama/libggml-cpu-haswell.so")
        .contains("rm -f")
        .endsWith("exec /bin/ollama serve");
  }

  @Test
  void everyRunnerThatLoadedTheBackendIsReadFromTheContainerLog() {
    assertThat(EvalOllamaCpuBackend.loadedBackends(HASWELL_RUNNER_LOG + HASWELL_RUNNER_LOG))
        .containsExactly("haswell");
    assertThat(EvalOllamaCpuBackend.loadedBackends(HASWELL_RUNNER_LOG + ICELAKE_RUNNER_LOG))
        .containsExactlyInAnyOrder("haswell", "icelake");
    assertThat(EvalOllamaCpuBackend.loadedBackends("")).isEmpty();
  }

  @Test
  void aRunWhoseRunnersComputedWithThePinnedVariantPasses() {
    assertThat(EvalOllamaCpuBackend.requirePinnedBackendLoaded(HASWELL_RUNNER_LOG))
        .isEqualTo(EvalOllamaCpuBackend.PINNED);
  }

  /** The state of every run before the pin on an AVX-512 host: ggml picks its best variant. */
  @Test
  void aRunThatComputedWithAnotherVariantIsRefused() {
    assertThatThrownBy(
            () ->
                EvalOllamaCpuBackend.requirePinnedBackendLoaded(
                    HASWELL_RUNNER_LOG + ICELAKE_RUNNER_LOG))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("icelake")
        .hasMessageContaining("haswell");
  }

  /** No runner has reported yet - nothing proves which kernels the measurement used. */
  @Test
  void aRunWithoutAnyLoadedRunnerIsRefused() {
    assertThatThrownBy(() -> EvalOllamaCpuBackend.requirePinnedBackendLoaded("starting server\n"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no model runner");
  }
}
