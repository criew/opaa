package io.opaa.eval;

import com.github.dockerjava.api.command.CreateContainerCmd;
import java.io.IOException;
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
 * AVX2-only one. Every x86-64 runner executes {@link #PINNED}; all other variants are removed
 * before the server starts, and {@link #requirePinnedBackendLoaded} proves from the runner log that
 * the measurement used it.
 */
final class EvalOllamaCpuBackend {

  static final String PINNED = "haswell";

  private static final String LIBRARY_DIR = "/usr/lib/ollama";

  private static final Pattern LOADED_BACKEND =
      Pattern.compile("loaded CPU backend from \\S*/libggml-cpu-([A-Za-z0-9_]+)\\.so");

  private EvalOllamaCpuBackend() {}

  /** A {@code createContainerCmdModifier} for the harness's {@link OllamaContainer}. */
  static void pin(CreateContainerCmd cmd) {
    cmd.withEntrypoint("/bin/sh", "-c").withCmd(startScript());
  }

  /** Fails the container start if the image no longer ships the pinned variant. */
  static String startScript() {
    String pinnedLibrary = LIBRARY_DIR + "/libggml-cpu-" + PINNED + ".so";
    return "test -f "
        + pinnedLibrary
        + " || { echo 'pinned ggml CPU backend missing: "
        + pinnedLibrary
        + "' >&2; exit 1; }; for f in "
        + LIBRARY_DIR
        + "/libggml-cpu-*.so; do [ \"$f\" = "
        + pinnedLibrary
        + " ] || rm -f \"$f\"; done; exec /bin/ollama serve";
  }

  /** The variants every model runner in {@code containerLogs} reported loading. */
  static Set<String> loadedBackends(String containerLogs) {
    Set<String> backends = new TreeSet<>();
    Matcher matcher = LOADED_BACKEND.matcher(containerLogs);
    while (matcher.find()) {
      backends.add(matcher.group(1));
    }
    return backends;
  }

  /**
   * Returns {@link #PINNED} if at least one model runner loaded it and none loaded anything else;
   * throws otherwise. Call after the first embedding or chat call, when a runner has started.
   */
  static String requirePinnedBackendLoaded(String containerLogs) {
    Set<String> loaded = loadedBackends(containerLogs);
    if (loaded.isEmpty()) {
      throw new IllegalStateException(
          "The eval Ollama container reports no model runner that loaded a ggml CPU backend yet,"
              + " so nothing proves the measurement computed with the pinned '"
              + PINNED
              + "' kernels.");
    }
    if (!loaded.equals(Set.of(PINNED))) {
      throw new IllegalStateException(
          "The eval Ollama container computed with ggml CPU backend(s) "
              + loaded
              + " instead of only the pinned '"
              + PINNED
              + "'. A different kernel set changes greedy generation and embedding vectors, so this"
              + " run is not comparable to any committed baseline (ADR-0012, Nachtrag CPU-Backend).");
    }
    return PINNED;
  }

  /**
   * {@link #requirePinnedBackendLoaded} against the running container, logging the host CPU the
   * runner computed on so a CI log shows that different runners measured with the same kernels.
   */
  static String verify(OllamaContainer container, Logger log) {
    String backend = requirePinnedBackendLoaded(container.getLogs());
    String hostCpu = describeHostCpu(container);
    String line = "Ollama CPU backend: " + backend + " (host: " + hostCpu + ")";
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
