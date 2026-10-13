package io.opaa.llm;

/**
 * The continuously readable state of the transcription role. Carries no access key in any form.
 *
 * @param baseUrl the configured address, {@code null} while unset or rejected for carrying
 *     credentials.
 * @param diagnostic a short technical English statement, never containing audio, recognised text or
 *     the endpoint's response body; {@code null} when there is nothing beyond {@code state}.
 * @param timedOut whether the last failure was the endpoint not answering within the timeout.
 * @param baseUrlRejected whether the configured address was refused for carrying credentials.
 */
public record TranscriptionRoleStatus(
    TranscriptionRoleState state,
    String baseUrl,
    String modelIdentifier,
    String diagnostic,
    boolean timedOut,
    boolean baseUrlRejected) {

  /** The role's switch is off. */
  public static TranscriptionRoleStatus disabled() {
    return new TranscriptionRoleStatus(
        TranscriptionRoleState.DISABLED, null, null, null, false, false);
  }
}
