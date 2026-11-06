package io.opaa.llm;

/**
 * The states of the text recognition model role, the same four as {@link TranscriptionRoleState}:
 * deliberately off, usable, and two kinds of switched on but broken.
 */
public enum TextRecognitionRoleState {

  /** The switch of the model administration or the operation's switch is off. */
  DISABLED,

  /** Switched on, configured, and the service answered the last probe or call. */
  READY,

  /** Switched on, but no usable address (or, for a vision model, no model) is configured. */
  UNCONFIGURED,

  /** Switched on and configured, but the service did not answer. */
  UNREACHABLE
}
