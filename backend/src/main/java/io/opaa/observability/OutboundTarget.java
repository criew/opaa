package io.opaa.observability;

import java.util.Locale;

/**
 * The fixed set of services an outbound call of {@code opaa.outbound.requests} goes to (ADR-0071,
 * Entscheidung 8). Chat and embedding are not among them: Spring AI measures those as {@code
 * gen_ai.client.operation}. {@link #SANDBOX} is the code sandbox of the agent loop.
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
  CLAMAV;

  /** The value of the {@code target} tag. */
  public String tagValue() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** Whether calls to this target name a {@code kind}: the connector type or directory provider. */
  public boolean hasKind() {
    return this == CONNECTOR || this == DIRECTORY;
  }
}
