package io.opaa.llm;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import io.opaa.api.types.TextRecognitionConnectionType;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@code opaa.model.role.available}: 1 or 0 per configured model role ({@code role}: chat,
 * embedding, rerank, transcription, ocr, vision), from the state the roles keep anyway and never
 * from a call of its own. Rerank, transcription and text recognition report their state of the
 * health page ({@link OptionalRoleState#READY} 1, {@link OptionalRoleState#UNREACHABLE} 0); chat
 * and embedding are available while configured unless the last real call of the role's model, or
 * for embedding its health probe, failed to reach it ({@link ModelCallOutcomes}). A role that is
 * switched off or not configured has no series. {@link #refresh} reads the state on every instance
 * (ADR-0053), so a scrape never waits for the database.
 */
@Component
public class ModelRoleAvailability {

  static final String METRIC = "opaa.model.role.available";

  private static final Logger log = LoggerFactory.getLogger(ModelRoleAvailability.class);

  private final List<String> roles = new ArrayList<>();
  private final List<Supplier<Boolean>> readings = new ArrayList<>();
  private final Supplier<TextRecognitionConnectionType> textRecognitionType;
  private final Supplier<OptionalRoleStatus> textRecognition;
  private final MultiGauge gauge;

  @Autowired
  ModelRoleAvailability(
      ActiveChatModelResolver chatModels,
      EmbeddingModelService embeddingModels,
      RerankRoleStatusProvider rerank,
      TranscriptionRoleStatusProvider transcription,
      TextRecognitionModelRole textRecognition,
      ModelCallOutcomes outcomes,
      MeterRegistry meterRegistry) {
    this(
        () -> chatModel(chatModels),
        outcomes::chatAvailable,
        () ->
            embeddingModels
                .installed()
                .map(EmbeddingModelSettings::getModelIdentifier)
                .orElse(null),
        outcomes::embeddingAvailable,
        rerank::currentStatus,
        transcription::currentStatus,
        textRecognition::currentStatus,
        () -> textRecognition.endpoint().connectionType(),
        meterRegistry);
  }

  /**
   * @param chatModel the identifier of the chat model, {@code null} without one
   * @param embeddingModel the identifier of the embedding model, {@code null} without one
   */
  ModelRoleAvailability(
      Supplier<String> chatModel,
      Predicate<String> chatAvailable,
      Supplier<String> embeddingModel,
      Predicate<String> embeddingAvailable,
      Supplier<OptionalRoleStatus> rerank,
      Supplier<OptionalRoleStatus> transcription,
      Supplier<OptionalRoleStatus> textRecognition,
      Supplier<TextRecognitionConnectionType> textRecognitionType,
      MeterRegistry meterRegistry) {
    add("chat", () -> availability(chatModel.get(), chatAvailable));
    add("embedding", () -> availability(embeddingModel.get(), embeddingAvailable));
    add("rerank", () -> of(rerank.get()));
    add("transcription", () -> of(transcription.get()));
    this.textRecognition = textRecognition;
    this.textRecognitionType = textRecognitionType;
    this.gauge =
        MultiGauge.builder(METRIC)
            .description("Whether a configured model role is available, 1 or 0")
            .register(meterRegistry);
  }

  private void add(String role, Supplier<Boolean> reading) {
    roles.add(role);
    readings.add(reading);
  }

  /**
   * Reads every role anew. Runs on every instance without a lock: it only reads, and each instance
   * reports its own gauge (ScheduledRunLockStructureTest#RUNS_ON_EVERY_INSTANCE). A role whose
   * state cannot be read keeps no series until the next read.
   */
  @Scheduled(initialDelay = 0, fixedDelayString = "PT30S")
  public void refresh() {
    List<MultiGauge.Row<?>> rows = new ArrayList<>();
    for (int i = 0; i < roles.size(); i++) {
      row(rows, roles.get(i), readings.get(i));
    }
    row(rows, null, () -> of(textRecognition.get()));
    gauge.register(rows, true);
  }

  private void row(List<MultiGauge.Row<?>> rows, String role, Supplier<Boolean> reading) {
    try {
      Boolean available = reading.get();
      if (available == null) {
        return;
      }
      String name = role != null ? role : textRecognitionRole();
      rows.add(MultiGauge.Row.of(Tags.of("role", name), available ? 1 : 0));
    } catch (RuntimeException e) {
      log.debug("The availability of a model role could not be read: {}", e.getMessage());
    }
  }

  /** {@code vision} for a vision model, {@code ocr} for a text recognition service. */
  private String textRecognitionRole() {
    return textRecognitionType.get() == TextRecognitionConnectionType.OPENAI_VISION
        ? "vision"
        : "ocr";
  }

  /** 1 or 0 for a role in use, {@code null} for one switched off or not configured. */
  private static Boolean of(OptionalRoleStatus status) {
    return switch (status.state()) {
      case READY -> true;
      case UNREACHABLE -> false;
      case DISABLED, UNCONFIGURED -> null;
    };
  }

  private static Boolean availability(String model, Predicate<String> available) {
    return model == null ? null : available.test(model);
  }

  private static String chatModel(ActiveChatModelResolver chatModels) {
    try {
      return chatModels.resolveDescription().modelIdentifier();
    } catch (NoActiveChatModelException e) {
      return null;
    }
  }
}
