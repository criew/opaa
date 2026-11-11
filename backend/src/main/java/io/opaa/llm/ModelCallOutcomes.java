package io.opaa.llm;

import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.observation.EmbeddingModelObservationContext;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.stereotype.Component;

/**
 * The outcome of the last real call of each chat and embedding model on this instance (ADR-0053, je
 * Instanz), read from the observations Spring AI makes of every call anyway, so knowing it costs no
 * call of its own. The embedding role's health probe embeds through the same observed model, so its
 * result lands here as well. A success makes a model available; only a failure of reach, key or
 * server (transport, {@code 401}, {@code 403}, {@code 5xx}) makes it unavailable. A rejected
 * request or {@code 429} leaves it as it was: the model answered.
 *
 * <p>Outcomes are held per model identifier, so a call of another model (a title or decomposition
 * model of its own) never speaks for the role's model, and a model never called is available. When
 * the identifier asked for differs from the one asked for before, the role's model has changed and
 * everything held for other models is dropped; a lost or rebuilt connection of the invalidation bus
 * changes nothing here.
 */
@Component
public class ModelCallOutcomes implements ObservationHandler<Observation.Context> {

  /** Models whose outcome is held per role; more are ignored until the next change drops them. */
  static final int MAX_MODELS = 20;

  private final Map<String, Boolean> chat = new ConcurrentHashMap<>();
  private final Map<String, Boolean> embedding = new ConcurrentHashMap<>();
  private final AtomicReference<String> chatModel = new AtomicReference<>();
  private final AtomicReference<String> embeddingModel = new AtomicReference<>();

  @Override
  public boolean supportsContext(Observation.Context context) {
    return context instanceof ChatModelObservationContext
        || context instanceof EmbeddingModelObservationContext;
  }

  @Override
  public void onStop(Observation.Context context) {
    String model;
    Map<String, Boolean> outcomes;
    if (context instanceof ChatModelObservationContext call) {
      ChatOptions options = call.getRequest() == null ? null : call.getRequest().getOptions();
      model = options == null ? null : options.getModel();
      outcomes = chat;
    } else if (context instanceof EmbeddingModelObservationContext call) {
      EmbeddingOptions options = call.getRequest() == null ? null : call.getRequest().getOptions();
      model = options == null ? null : options.getModel();
      outcomes = embedding;
    } else {
      return;
    }
    if (model == null || (outcomes.size() >= MAX_MODELS && !outcomes.containsKey(model))) {
      return;
    }
    Throwable error = context.getError();
    if (error == null) {
      outcomes.put(model, true);
    } else if (unavailable(error)) {
      outcomes.put(model, false);
    }
  }

  /**
   * Whether the last call of the role's chat model {@code model} reached it, true before the first.
   */
  public boolean chatAvailable(String model) {
    return available(chat, chatModel, model);
  }

  /**
   * Whether the last call of the role's embedding model {@code model} reached it, true before the
   * first.
   */
  public boolean embeddingAvailable(String model) {
    return available(embedding, embeddingModel, model);
  }

  private static boolean available(
      Map<String, Boolean> outcomes, AtomicReference<String> roleModel, String model) {
    String previous = roleModel.getAndSet(model);
    if (previous != null && !Objects.equals(previous, model)) {
      outcomes.keySet().removeIf(held -> !held.equals(model));
    }
    return model == null || outcomes.getOrDefault(model, true);
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
