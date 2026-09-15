package io.opaa.eval;

import com.github.dockerjava.api.command.CreateContainerCmd;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.testcontainers.ollama.OllamaContainer;

/**
 * Pins the ggml CPU kernel set the eval Ollama container computes with (ADR-0012, Nachtrag
 * CPU-Backend). The image ships one library per instruction-set level and loads the best one the
 * host supports, so the same model digest computes with other kernels on an AVX-512 host than on an
 * AVX2-only one. Every x86-64 host executes {@link #PINNED}; all other variants are removed before
 * the server starts, and {@link #requirePinnedBackendLoaded} proves from the container log that
 * every model runner started so far used it.
 */
final class EvalOllamaCpuBackend {

  static final String PINNED = "haswell";

  /**
   * Recorded instead of a variant when {@code -Dopaa.eval.allowGpu=true} lets the GPU compute: the
   * CPU pin says nothing about such a run, which is never comparable to a committed baseline.
   */
  static final String GPU_ALLOWED = "unpinned: gpu allowed";

  static final String LIBRARY_DIR = "/usr/lib/ollama";

  private static final String SERVE_COMMAND = "/bin/ollama serve";

  private static final Pattern LOADED_BACKEND =
      Pattern.compile("loaded CPU backend from \\S*/libggml-cpu-([A-Za-z0-9_]+)\\.so");

  private EvalOllamaCpuBackend() {}

  /** A {@code createContainerCmdModifier} for the harness's {@link OllamaContainer}. */
  static void pin(CreateContainerCmd cmd) {
    cmd.withEntrypoint("/bin/sh", "-c").withCmd(startScript(LIBRARY_DIR, SERVE_COMMAND));
  }

  /**
   * Deletes every {@code libggml-cpu-*.so} in {@code libraryDir} except the pinned one, then execs
   * {@code serveCommand}; exits non-zero without starting it if the pinned library is missing.
   */
  static String startScript(String libraryDir, String serveCommand) {
    String pinnedLibrary = libraryDir + "/libggml-cpu-" + PINNED + ".so";
    return "test -f '"
        + pinnedLibrary
        + "' || { echo 'pinned ggml CPU backend missing: "
        + pinnedLibrary
        + "' >&2; exit 1; }; for f in '"
        + libraryDir
        + "'/libggml-cpu-*.so; do [ \"$f\" = '"
        + pinnedLibrary
        + "' ] || rm -f \"$f\"; done; exec "
        + serveCommand;
  }

  /** The variant of every "loaded CPU backend" line in {@code containerLogs}, in log order. */
  static List<String> loadedBackends(String containerLogs) {
    List<String> backends = new ArrayList<>();
    Matcher matcher = LOADED_BACKEND.matcher(containerLogs);
    while (matcher.find()) {
      backends.add(matcher.group(1));
    }
    return backends;
  }

  /**
   * Returns {@link #PINNED} if at least {@code minimumRunners} model runners reported loading a CPU
   * backend and every one of them loaded the pinned variant; throws otherwise.
   */
  static String requirePinnedBackendLoaded(String containerLogs, int minimumRunners) {
    List<String> loaded = loadedBackends(containerLogs);
    if (loaded.size() < minimumRunners) {
      throw new IllegalStateException(
          "The eval Ollama container reports "
              + loaded.size()
              + " model runner(s) that loaded a ggml CPU backend, expected at least "
              + minimumRunners
              + " (embedding runner, plus the chat runner when decomposition is enabled) - nothing"
              + " proves those runners computed with the pinned '"
              + PINNED
              + "' kernels.");
    }
    Set<String> variants = new TreeSet<>(loaded);
    if (!variants.equals(Set.of(PINNED))) {
      throw new IllegalStateException(
          "The eval Ollama container computed with ggml CPU backend(s) "
              + variants
              + " instead of only the pinned '"
              + PINNED
              + "'. A different kernel set changes greedy generation and embedding vectors, so this"
              + " run is not comparable to any committed baseline (ADR-0012, Nachtrag CPU-Backend).");
    }
    return PINNED;
  }

  /**
   * The value a report's {@code ollamaCpuBackend} carries: {@link #GPU_ALLOWED} when the GPU
   * opt-out is set, otherwise {@link #requirePinnedBackendLoaded} against the running container.
   * Logs the host CPU so a CI log shows which machine measured with the pinned kernels.
   */
  static String verify(
      OllamaContainer container, boolean allowGpu, int minimumRunners, Logger log) {
    String backend =
        allowGpu ? GPU_ALLOWED : requirePinnedBackendLoaded(container.getLogs(), minimumRunners);
    String line = "Ollama CPU backend: " + backend + " (host: " + describeHostCpu(container) + ")";
    log.info(line);
    System.out.println(line);
    return backend;
  }

  private static String describeHostCpu(OllamaContainer container) {
    try {
      var result =
          container.execInContainer(
              "/bin/sh",
              "-c",
              "grep -m1 'model name' /proc/cpuinfo | cut -d: -f2;"
                  + " grep -qw avx512f /proc/cpuinfo && echo 'AVX-512: yes' || echo 'AVX-512: no'");
      return result.getStdout().strip().replaceAll("\\s*\\n\\s*", ", ");
    } catch (IOException e) {
      return "unknown (" + e.getMessage() + ")";
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "unknown (interrupted)";
    }
  }
}
