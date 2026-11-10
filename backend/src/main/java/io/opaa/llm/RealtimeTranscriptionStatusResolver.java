package io.opaa.llm;

/** The state of the live upstream for a stored transcription endpoint; never probes. */
@FunctionalInterface
public interface RealtimeTranscriptionStatusResolver {

  /** {@link OptionalRoleState#DISABLED} while no live upstream is stored or the role is off. */
  OptionalRoleStatus realtimeStatusFor(TranscriptionEndpoint endpoint);
}
