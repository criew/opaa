package io.opaa.llm;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Thrown by {@link ActiveChatModelResolver} when {@code llm_models} has no active row (#758) - a
 * Systemverwaltung deleted every model, or a fresh installation has not activated one yet. A {@link
 * ResponseStatusException} subclass so it needs no special handling anywhere it can surface: {@code
 * io.opaa.api.GlobalExceptionHandler}'s existing {@code ResponseStatusException} handler turns it
 * into the same German, user-facing error body every other domain error in this codebase already
 * produces, and {@code io.opaa.chat.ChatTitleGenerationService#generateTitleAsync}'s existing
 * catch-all already swallows it exactly like any other title-generation failure.
 */
public class NoActiveChatModelException extends ResponseStatusException {

  NoActiveChatModelException() {
    super(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Es ist derzeit kein aktives Chat-Modell konfiguriert. Bitte wenden Sie sich an die"
            + " Systemverwaltung.");
  }
}
