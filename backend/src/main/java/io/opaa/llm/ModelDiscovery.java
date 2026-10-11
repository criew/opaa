package io.opaa.llm;

import java.util.List;

/**
 * What an endpoint answered when asked for its models: either a list, possibly empty, or exactly
 * one {@link Failure} with a German, user-facing message. Never both.
 */
public record ModelDiscovery(
    boolean success,
    Failure failure,
    String message,
    boolean ollama,
    List<DiscoveredModel> models) {

  /** Why no model list could be read; the names match the spec's LlmModelDiscoveryFailure. */
  public enum Failure {
    INVALID_ADDRESS,
    UNREACHABLE,
    UNAUTHENTICATED,
    NO_MODEL_LIST,
    UNEXPECTED_RESPONSE
  }

  public ModelDiscovery {
    models = List.copyOf(models);
  }

  public static ModelDiscovery found(boolean ollama, List<DiscoveredModel> models) {
    String message =
        switch (models.size()) {
          case 0 -> "Der Endpunkt meldet keine Modelle.";
          case 1 -> "1 Modell gefunden.";
          default -> models.size() + " Modelle gefunden.";
        };
    return new ModelDiscovery(true, null, message, ollama, models);
  }

  public static ModelDiscovery failed(Failure failure, String message) {
    return new ModelDiscovery(false, failure, message, false, List.of());
  }
}
