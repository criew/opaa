package io.opaa.llm;

import io.opaa.api.types.TextRecognitionConnectionType;

/**
 * The continuously readable state of the text recognition role. Carries no access key in any form.
 *
 * @param connectionType the configured connection type, {@code null} while the role is off.
 * @param baseUrl the configured address, {@code null} while unset or rejected for carrying
 *     credentials.
 * @param modelIdentifier the vision model, {@code null} for docling-serve or while unset.
 * @param diagnostic a short technical English statement, never containing document content or the
 *     service's response body; {@code null} when there is nothing beyond {@code state}.
 * @param timedOut whether the last failure was the service not answering within the timeout.
 * @param baseUrlRejected whether the configured address was refused for carrying credentials.
 */
public record TextRecognitionRoleStatus(
    TextRecognitionRoleState state,
    TextRecognitionConnectionType connectionType,
    String baseUrl,
    String modelIdentifier,
    String diagnostic,
    boolean timedOut,
    boolean baseUrlRejected) {

  /** The role's switch or the operation's switch is off. */
  public static TextRecognitionRoleStatus disabled() {
    return new TextRecognitionRoleStatus(
        TextRecognitionRoleState.DISABLED, null, null, null, null, false, false);
  }
}
