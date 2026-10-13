package io.opaa.llm;

/**
 * What a reader of the transcription role's state needs - the administration status page and the
 * health indicator. Answers from configuration and the last known endpoint state, never with a
 * network round trip.
 */
@FunctionalInterface
public interface TranscriptionRoleStatusProvider {

  TranscriptionRoleStatus currentStatus();
}
