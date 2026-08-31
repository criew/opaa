package io.opaa.eval;

/**
 * Resolves whether a retrieval evaluation harness should talk to a Testcontainers-managed Ollama
 * (the default, unchanged behavior) or to an external, already-running Ollama instance selected via
 * the {@value #BASE_URL_PROPERTY} system property (issue #1076) — e.g. a native host Ollama using a
 * GPU Testcontainers itself cannot pass through (AMD GPUs under Docker/WSL2).
 *
 * <p>A run against an external endpoint is deliberately not comparable to the pinned CI/baseline
 * numbers, analogous to the {@code -Dopaa.eval.allowGpu} opt-out — see eval/README.md, "Externer
 * Ollama-Endpunkt".
 */
final class EvalOllamaEndpoint {

  static final String BASE_URL_PROPERTY = "opaa.eval.ollamaBaseUrl";

  private EvalOllamaEndpoint() {}

  /**
   * The configured external base URL, or {@code null} if {@value #BASE_URL_PROPERTY} is unset or
   * blank — the latter is treated the same as unset so a stray {@code -Dopaa.eval.ollamaBaseUrl=}
   * does not silently disable the Testcontainer without pointing anywhere.
   */
  static String externalBaseUrl() {
    String value = System.getProperty(BASE_URL_PROPERTY);
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  static boolean isExternal() {
    return externalBaseUrl() != null;
  }

  /**
   * The value a report's "ollamaImage" field should carry: the pinned Testcontainer image when not
   * external, or a marker naming the external endpoint otherwise — a report must never claim the
   * pinned image ran when no container was ever started (issue #1076 review).
   */
  static String describeImageOrEndpoint(String pinnedImage) {
    return isExternal() ? "extern: " + externalBaseUrl() : pinnedImage;
  }
}
