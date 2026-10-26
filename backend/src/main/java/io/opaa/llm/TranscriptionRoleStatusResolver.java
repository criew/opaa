package io.opaa.llm;

/**
 * The state of the transcription role for a given stored endpoint, from the last known observation
 * and without probing - what the model administration shows right after a change.
 */
public interface TranscriptionRoleStatusResolver {

  TranscriptionRoleStatus statusFor(TranscriptionEndpoint endpoint);
}
