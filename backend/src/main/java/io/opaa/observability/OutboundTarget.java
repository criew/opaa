package io.opaa.observability;

import java.util.Locale;

/**
 * The fixed set of services an outbound call of {@code opaa.outbound.requests} goes to (ADR-0071,
 * Entscheidung 8). Chat and embedding calls are not among them: Spring AI measures those as {@code
 * gen_ai.client.operation}; {@link #MODEL_CHECK} is only the administration's connection test and
 * model discovery of a chat or embedding endpoint. {@link #SANDBOX} is the code sandbox of the
 * agent loop, {@link #ORIGINAL_STORE} the S3 store of uploaded originals.
 */
public enum OutboundTarget {
  RERANK,
  TRANSCRIPTION,
  OCR,
  VISION,
  SANDBOX,
  CONNECTOR,
  OIDC,
  DIRECTORY,
  CLAMAV,
  MODEL_CHECK,
  ORIGINAL_STORE;

  /** The value of the {@code target} tag. */
  public String tagValue() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** Whether calls to this target name a {@code kind}: the connector type or directory provider. */
  public boolean hasKind() {
    return this == CONNECTOR || this == DIRECTORY;
  }
}
