package io.opaa.llm;

import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.opaa.instance.InvalidationSubscriber;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 * model of its own) never speaks for the role's model, and a model never called is available. A
 * change of the chat or embedding model drops everything held.
 */
@Component
public class ModelCallOutcomes
    implements ObservationHandler<Observation.Context>, InvalidationSubscriber {

  /** Models whose outcome is held per role; more are ignored until the next change drops all. */
  static final int MAX_MODELS = 20;

  private final Map<String, Boolean> chat = new ConcurrentHashMap<>();
  private final Map<String, Boolean> embedding = new ConcurrentHashMap<>();

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

  /** Whether the last call of chat model {@code model} reached it, true before the first. */
  public boolean chatAvailable(String model) {
    return chat.getOrDefault(model, true);
  }

  /** Whether the last call of embedding model {@code model} reached it, true before the first. */
  public boolean embeddingAvailable(String model) {
    return embedding.getOrDefault(model, true);
  }

  @Override
  public Set<String> invalidationTopics() {
    return Set.of(
        ActiveChatModelResolver.INVALIDATION_TOPIC, InstalledEmbeddingModel.INVALIDATION_TOPIC);
  }

  @Override
  public void invalidateAll() {
    chat.clear();
    embedding.clear();
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
