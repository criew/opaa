package io.opaa.query.toolcheck;

import com.openai.errors.OpenAIServiceException;
import io.opaa.observability.OutboundMetrics;
import io.opaa.observability.OutboundOutcome;
import io.opaa.observability.OutboundPathClass;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * Counts every call of a tool check's model as one outbound call to {@code model_check} on the
 * background path, with the outcome from the status of the endpoint's answer or the class of the
 * failure (ADR-0071, Entscheidung 8); a stream given up before its end counts as a timeout, as the
 * measurement gives up only at its deadline. The model itself is built without observations, so the
 * check adds nothing to {@code gen_ai.*} or to the availability of a model role.
 */
record MeteredCheckChatModel(ChatModel delegate, OutboundMetrics metrics) implements ChatModel {

  @Override
  public ChatResponse call(Prompt prompt) {
    long start = System.nanoTime();
    try {
      ChatResponse response = delegate.call(prompt);
      record(OutboundOutcome.SUCCESS, start);
      return response;
    } catch (RuntimeException e) {
      record(outcome(e), start);
      throw e;
    }
  }

  @Override
  public Flux<ChatResponse> stream(Prompt prompt) {
    return Flux.defer(
        () -> {
          long start = System.nanoTime();
          return delegate.stream(prompt)
              .doOnComplete(() -> record(OutboundOutcome.SUCCESS, start))
              .doOnError(e -> record(outcome(e), start))
              .doOnCancel(() -> record(OutboundOutcome.TIMEOUT, start));
        });
  }

  @Override
  public ChatOptions getOptions() {
    return delegate.getOptions();
  }

  private void record(OutboundOutcome outcome, long start) {
    metrics.record(OutboundPathClass.BACKGROUND, outcome, System.nanoTime() - start);
  }

  /** The outcome of a failed call: the status of the endpoint's answer, else the failure class. */
  static OutboundOutcome outcome(Throwable failure) {
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
