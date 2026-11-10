package io.opaa.api.types;

/**
 * Whether a chat model calls tools reliably, recorded for one combination of endpoint and model
 * identifier. {@link #UNVERIFIED} is the default and behaves like {@link #NOT_CAPABLE}: a turn that
 * needs tool calls is refused, never answered without its tools.
 */
public enum ToolCapability {
  UNVERIFIED,
  CAPABLE,
  NOT_CAPABLE
}
