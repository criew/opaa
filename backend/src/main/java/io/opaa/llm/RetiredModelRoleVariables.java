package io.opaa.llm;

import java.util.List;
import java.util.Map;
import org.springframework.core.env.Environment;

/**
 * The configuration variables that bound the rerank and transcription roles before ADR-0060
 * (Entscheidung 10). They are no longer read and never taken over; an environment that still sets
 * one, under its variable or its property name, is named at start and on the role's page, so a role
 * that was configured that way does not go off without a word. Names only, never values.
 */
public final class RetiredModelRoleVariables {

  /** Each retired rerank variable with the property it was bound to. */
  public static final Map<String, String> RERANK =
      Map.of(
          "OPAA_RERANK_ENABLED", "opaa.rerank.enabled",
          "OPAA_RERANK_BASE_URL", "opaa.rerank.base-url",
          "OPAA_RERANK_MODEL", "opaa.rerank.model",
          "OPAA_RERANK_API_KEY", "opaa.rerank.api-key");

  /** Each retired transcription variable with the property it was bound to. */
  public static final Map<String, String> TRANSCRIPTION =
      Map.of(
          "OPAA_TRANSCRIPTION_ENABLED", "opaa.transcription.enabled",
          "OPAA_TRANSCRIPTION_BASE_URL", "opaa.transcription.base-url",
          "OPAA_TRANSCRIPTION_MODEL", "opaa.transcription.model",
          "OPAA_TRANSCRIPTION_API_KEY", "opaa.transcription.api-key",
          "OPAA_TRANSCRIPTION_LANGUAGE", "opaa.transcription.language");

  private RetiredModelRoleVariables() {}

  /** The variables of {@code retired} that {@code environment} still sets, sorted by name. */
  public static List<String> setIn(Environment environment, Map<String, String> retired) {
    return retired.entrySet().stream()
        .filter(
            entry ->
                environment.containsProperty(entry.getKey())
                    || environment.containsProperty(entry.getValue()))
        .map(Map.Entry::getKey)
        .sorted()
        .toList();
  }
}
