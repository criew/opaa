package io.opaa.llm;

/**
 * The states of the transcription model role, the same four as {@link RerankRoleState}:
 * deliberately off, usable, and two kinds of switched on but broken.
 */
public enum TranscriptionRoleState {

  /** The switch of the model administration or the feature speech-input is off. */
  DISABLED,

  /** Switched on, configured, and the endpoint answered the last probe or call. */
  READY,

  /** Switched on, but no usable endpoint or model is configured. */
  UNCONFIGURED,

  /** Switched on and configured, but the endpoint did not answer. */
  UNREACHABLE
}
