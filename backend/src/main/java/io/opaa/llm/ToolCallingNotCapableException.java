package io.opaa.llm;

import io.opaa.api.types.ToolCapability;
import io.opaa.common.ServiceUnavailableException;

/**
 * Thrown by {@link ActiveChatModelResolver#resolveToolCallingChatClient()} when the active chat
 * model is not recorded as {@link ToolCapability#CAPABLE}: a turn that needs tool calls is refused
 * rather than answered without its tools. Like {@link NoActiveChatModelException} a regular state,
 * not an outage - the {@code 503} carries {@link #CODE} and says who can change it.
 */
public class ToolCallingNotCapableException extends ServiceUnavailableException {

  /** The {@code code} of the response. */
  public static final String CODE = "CHAT_MODEL_NOT_TOOL_CAPABLE";

  public ToolCallingNotCapableException(ToolCapability capability) {
    super(message(capability), CODE);
  }

  private static String message(ToolCapability capability) {
    if (capability == ToolCapability.NOT_CAPABLE) {
      return "Diese Anfrage braucht Werkzeugaufrufe, und das aktive Chat-Modell ist als nicht"
          + " fähig dafür eingetragen. Die Systemverwaltung kann unter Modelle → Chat-Modelle ein"
          + " werkzeugfähiges Modell aktivieren.";
    }
    return "Diese Anfrage braucht Werkzeugaufrufe, und für das aktive Chat-Modell ist noch nicht"
        + " geprüft, ob es Werkzeuge zuverlässig aufruft. Die Systemverwaltung kann die"
        + " Werkzeugfähigkeit unter Modelle → Chat-Modelle festhalten oder ein geprüftes Modell"
        + " aktivieren.";
  }
}
