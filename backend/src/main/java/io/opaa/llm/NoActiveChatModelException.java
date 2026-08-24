package io.opaa.llm;

import io.opaa.common.ServiceUnavailableException;

/**
 * Thrown by {@link ActiveChatModelResolver} when {@code llm_models} has no active row (#758) - a
 * Systemverwaltung deleted every model, or a fresh installation has not activated one yet. A {@link
 * ServiceUnavailableException} subclass so it needs no special handling anywhere it can surface:
 * {@code io.opaa.api.GlobalExceptionHandler}'s existing {@code ServiceUnavailableException} handler
 * turns it into the same German, user-facing error body every other domain error in this codebase
 * already produces, and {@code io.opaa.chat.ChatTitleGenerationService#generateTitleAsync}'s
 * existing catch-all already swallows it exactly like any other title-generation failure.
 */
public class NoActiveChatModelException extends ServiceUnavailableException {

  NoActiveChatModelException() {
    super(
        "Es ist derzeit kein aktives Chat-Modell konfiguriert. Bitte wenden Sie sich an die"
            + " Systemverwaltung.");
  }
}
