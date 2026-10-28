package io.opaa.llm;

import io.opaa.common.ServiceUnavailableException;

/**
 * Thrown by {@link ActiveChatModelResolver} when {@code llm_models} has no active row - an
 * installation that skipped its chat model step, or a Systemverwaltung that deleted every model. A
 * regular state, not an outage: the {@code 503} carries {@link #CODE}, which the chat explains with
 * the way into the model administration, and is not logged as a server error.
 */
public class NoActiveChatModelException extends ServiceUnavailableException {

  /** The {@code code} of the response; the chat shows its notice for it. */
  public static final String CODE = "CHAT_MODEL_NOT_CONFIGURED";

  NoActiveChatModelException() {
    super(
        "Es ist noch kein Chat-Modell eingerichtet. Die Systemverwaltung legt es unter Modelle →"
            + " Chat-Modelle an und aktiviert es.",
        CODE);
  }
}
