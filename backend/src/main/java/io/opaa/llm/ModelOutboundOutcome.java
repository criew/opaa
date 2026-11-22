package io.opaa.llm;

import com.openai.errors.OpenAIServiceException;
import io.opaa.observability.OutboundOutcome;

/**
 * The result class of a failed model call for a caller that measures the call itself in {@code
 * opaa.outbound.requests} (ADR-0071, Entscheidung 8): the status of the endpoint's answer when the
 * provider client carried one in the cause chain, else the class of the failure. Callers outside
 * {@code llm} use this instead of reading the provider client's exceptions themselves.
 */
public final class ModelOutboundOutcome {

  private ModelOutboundOutcome() {}

  /** The outcome of a call that ended with {@code failure}. */
  public static OutboundOutcome of(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof OpenAIServiceException service) {
        return OutboundOutcome.ofStatus(service.statusCode(), null);
      }
      if (cause.getCause() == cause) {
        break;
      }
    }
    return OutboundOutcome.ofFailure(failure);
  }
}
