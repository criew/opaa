package io.opaa.query.toolcheck;

import io.opaa.llm.ActiveChatModelResolver;
import io.opaa.llm.ChatResponses;
import io.opaa.llm.LlmModel;
import io.opaa.llm.LlmModelRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;

/**
 * Runs one tool check on the tool-check pool: a readiness call, then every case of the case base
 * against the model entry with the agent loop's {@code search_knowledge} definition, answered
 * without streaming like the agent loop. Each model call renews the heartbeat, each judged run the
 * progress; the end is written only while the row is still running. No transaction spans a model
 * call.
 */
@Component
class ToolCheckWorker {

  /** Time limit of one model call, as in the evaluation harness. */
  static final Duration MODEL_CALL_TIMEOUT = Duration.ofSeconds(300);

  static final String NOT_STATED = ToolCallingReport.NOT_STATED;

  private static final Logger log = LoggerFactory.getLogger(ToolCheckWorker.class);

  private final ToolCheckRepository repository;
  private final LlmModelRepository models;
  private final ActiveChatModelResolver chatModels;
  private final TransactionTemplate transactions;
  private final Clock clock;

  ToolCheckWorker(
      ToolCheckRepository repository,
      LlmModelRepository models,
      ActiveChatModelResolver chatModels,
      TransactionTemplate transactions,
      Clock clock) {
    this.repository = repository;
    this.models = models;
    this.chatModels = chatModels;
    this.transactions = transactions;
    this.clock = clock;
  }

  void run(UUID checkId) {
    Optional<ToolCheck> found = repository.findById(checkId);
    Optional<LlmModel> model = found.flatMap(check -> models.findById(check.getModelId()));
    if (found.isEmpty() || model.isEmpty()) {
      return;
    }
    ToolCheck check = found.get();
    try {
      measure(check, model.get());
    } catch (RuntimeException e) {
      log.warn("Tool check {} failed: {}", checkId, e.toString());
      fail(checkId, "Die Prüfung ist abgebrochen: " + e.getClass().getSimpleName() + ".");
    }
  }

  private void measure(ToolCheck check, LlmModel model) {
    UUID checkId = check.getId();
    ChatModel chatModel =
        new HeartbeatChatModel(
            chatModels.chatModelFor(model, MODEL_CALL_TIMEOUT),
            () -> write(() -> repository.beat(checkId)));
    String answeredModel;
    try {
      ChatResponse ready = chatModel.call(new Prompt("Antworte mit dem Wort: bereit"));
      String text = ChatResponses.text(ready);
      if (text == null || text.isBlank()) {
        fail(checkId, "Das Modell hat auf die Bereitschaftsprobe keine Antwort gegeben.");
        return;
      }
      answeredModel = ChatResponses.model(ready);
    } catch (RuntimeException e) {
      log.info("Tool check {}: readiness call failed: {}", checkId, e.toString());
      fail(
          checkId,
          "Das Modell ist nicht erreichbar oder hat die Bereitschaftsprobe abgelehnt. Prüfen Sie"
              + " Adresse und Schlüssel mit „Verbindung testen“.");
      return;
    }
    ToolCallingCaseBase caseBase = ToolCallingCaseBase.load();
    ToolCallingTool tool = ToolCallingTool.production(caseBase);
    Instant start = Instant.now(clock);
    List<ToolCallingReport.CaseRun> runs =
        ToolCallingMeasurement.run(
            caseBase,
            tool,
            chatModel,
            check.getRepetitions(),
            false,
            MODEL_CALL_TIMEOUT,
            (done, total, run) -> write(() -> repository.recordProgress(checkId, done)));
    ToolCallingReport report =
        ToolCallingReport.of(
            start,
            new ToolCallingReport.Combination(
                model.getModelIdentifier(),
                answeredModel,
                NOT_STATED,
                NOT_STATED,
                NOT_STATED,
                NOT_STATED,
                NOT_STATED,
                check.getBaseUrl(),
                model.getTemperature() == null
                    ? NOT_STATED
                    : model.getTemperature().toPlainString(),
                model.getMaxTokens(),
                false),
            new ToolCallingReport.CaseBase(
                ToolCallingCaseBase.SOURCE_PATH,
                caseBase.caseBaseVersion(),
                caseBase.manifestSha256(),
                caseBase.cases().size(),
                check.getRepetitions(),
                tool.definition().name()),
            Duration.between(start, Instant.now(clock)).toMillis() / 1000.0,
            runs);
    write(
        () ->
            repository.complete(checkId, report.runId(), report.toJson(), report.renderMarkdown()));
    log.info(
        "Tool check {} of model {} completed: {}",
        checkId,
        model.getId(),
        report.capabilityFloor().met() ? "floor met" : "floor not met");
  }

  private void fail(UUID checkId, String failure) {
    write(() -> repository.fail(checkId, failure));
  }

  private void write(Runnable update) {
    transactions.executeWithoutResult(status -> update.run());
  }

  /** Renews the heartbeat before every model call, so a long case never looks abandoned. */
  private record HeartbeatChatModel(ChatModel delegate, Runnable beat) implements ChatModel {

    @Override
    public ChatResponse call(Prompt prompt) {
      beat.run();
      return delegate.call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
      beat.run();
      return delegate.stream(prompt);
    }

    @Override
    public ChatOptions getOptions() {
      return delegate.getOptions();
    }
  }
}
