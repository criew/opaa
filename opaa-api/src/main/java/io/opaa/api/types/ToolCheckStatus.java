package io.opaa.api.types;

/**
 * The state of a tool check of a chat model: running, completed with a result, failed because the
 * endpoint did not answer, or aborted because the instance running it stopped.
 */
public enum ToolCheckStatus {
  RUNNING,
  COMPLETED,
  FAILED,
  ABORTED
}
