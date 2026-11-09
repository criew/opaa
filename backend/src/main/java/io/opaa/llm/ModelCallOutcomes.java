package io.opaa.llm;

import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.embedding.observation.EmbeddingModelObservationContext;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.stereotype.Component;

/**
 * The outcome of the last real call of the chat and of the embedding model on this instance
 * (ADR-0053, je Instanz), read from the observations Spring AI makes of every call anyway, so
 * knowing it costs no call of its own. A success makes the role available; only a failure of reach,
 * key or server (transport, {@code 401}, {@code 403}, {@code 5xx}) makes it unavailable. A rejected
 * request or {@code 429} leaves it as it was: the model answered.
 */
@Component
public class ModelCallOutcomes implements ObservationHandler<Observation.Context> {

  private final AtomicBoolean chatAvailable = new AtomicBoolean(true);
  private final AtomicBoolean embeddingAvailable = new AtomicBoolean(true);

  @Override
  public boolean supportsContext(Observation.Context context) {
    return context instanceof ChatModelObservationContext
        || context instanceof EmbeddingModelObservationContext;
  }

  @Override
  public void onStop(Observation.Context context) {
    AtomicBoolean available =
        context instanceof ChatModelObservationContext ? chatAvailable : embeddingAvailable;
    Throwable error = context.getError();
    if (error == null) {
      available.set(true);
    } else if (unavailable(error)) {
      available.set(false);
    }
  }

  /** Whether the last call of the chat model reached it, true before the first. */
  public boolean chatAvailable() {
    return chatAvailable.get();
  }

  /** Whether the last call of the embedding model reached it, true before the first. */
  public boolean embeddingAvailable() {
    return embeddingAvailable.get();
  }

  /** Whether {@code failure} says the model could not be reached or refused the key. */
  static boolean unavailable(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof OpenAIServiceException service) {
        int status = service.statusCode();
        return status == 401 || status == 403 || status >= 500;
      }
      if (cause instanceof OpenAIIoException
          || cause instanceof TransientAiException
          || cause instanceof IOException) {
        return true;
      }
      if (cause.getCause() == cause) {
        break;
      }
    }
    return false;
  }
}
