package io.opaa.llm;

/**
 * The administration stored a changed rerank, transcription or text recognition model role,
 * published after the commit on the instance that stored it. Other instances learn of the change
 * through the invalidation bus and their next scheduled probe.
 */
public record ModelRoleSettingsChangedEvent(Role role) {

  /** The role that changed. */
  public enum Role {
    RERANK,
    TRANSCRIPTION,
    TEXT_RECOGNITION
  }
}
