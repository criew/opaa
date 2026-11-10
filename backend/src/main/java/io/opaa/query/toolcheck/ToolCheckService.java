package io.opaa.query.toolcheck;

import io.opaa.common.ConflictException;
import io.opaa.common.DatabaseClock;
import io.opaa.common.NotFoundException;
import io.opaa.common.TransactionCallbacks;
import io.opaa.common.ValidationException;
import io.opaa.llm.LlmModel;
import io.opaa.llm.LlmModelService;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Starts and reads tool checks of chat model entries (#1753): the tool-calling case base run
 * against a stored entry, so an installation measures its own combination. Only offered while
 * agents are switched on ({@link ToolCheckGate}); otherwise every path answers 404 like an unknown
 * model. A check measures and records; it never sets the model's tool capability.
 *
 * <p>Callers are system administrators only, guarded at the controller.
 */
@Service
public class ToolCheckService {

  /** Checks kept per model, the newest included; older ones go when a new one starts. */
  static final int KEPT_PER_MODEL = 5;

  /**
   * A running check whose heartbeat is older than this is given up as aborted: two model calls at
   * their time limit plus a margin, so a slow but living check is never taken for dead.
   */
  static final long STALE_SECONDS = 2 * ToolCheckWorker.MODEL_CALL_TIMEOUT.toSeconds() + 300;

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final ToolCheckGate gate;
  private final ToolCheckRepository repository;
  private final LlmModelService models;
  private final DatabaseClock databaseClock;
  private final ToolCheckWorker worker;
  private final TaskExecutor executor;

  ToolCheckService(
      ToolCheckGate gate,
      ToolCheckRepository repository,
      LlmModelService models,
      DatabaseClock databaseClock,
      ToolCheckWorker worker,
      TaskExecutor toolCheckExecutor) {
    this.gate = gate;
    this.repository = repository;
    this.models = models;
    this.databaseClock = databaseClock;
    this.worker = worker;
    this.executor = toolCheckExecutor;
  }

  /**
   * Starts a check of {@code modelId} with {@code repetitions} runs per case; it runs after the
   * commit on this instance's tool-check pool.
   *
   * @throws NotFoundException while agents are off or when the model does not exist
   * @throws ConflictException while another check runs
   */
  @Transactional
  public ToolCheckView start(UUID modelId, int repetitions) {
    requireOpen();
    if (repetitions < 1 || repetitions > 3) {
      throw new ValidationException("Die Zahl der Läufe je Fall muss zwischen 1 und 3 liegen.");
    }
    LlmModel model = models.getModel(modelId);
    repository.abortStale(STALE_SECONDS);
    List<ToolCheck> earlier = repository.findByModelIdOrderByStartedAtDesc(modelId);
    if (earlier.size() >= KEPT_PER_MODEL) {
      repository.deleteAll(earlier.subList(KEPT_PER_MODEL - 1, earlier.size()));
    }
    int total = ToolCallingCaseBase.load().cases().size() * repetitions;
    ToolCheck check =
        new ToolCheck(
            modelId,
            model.getBaseUrl(),
            model.getModelIdentifier(),
            repetitions,
            total,
            databaseClock.now());
    try {
      repository.saveAndFlush(check);
    } catch (DataIntegrityViolationException e) {
      throw new ConflictException(
          "Es läuft bereits eine Werkzeugprüfung. Warten Sie, bis sie beendet ist.", e);
    }
    UUID checkId = check.getId();
    TransactionCallbacks.afterCommitOrNow(() -> executor.execute(() -> worker.run(checkId)));
    return view(check, model);
  }

  /** The newest check of {@code modelId}, after giving up stale ones. */
  @Transactional
  public ToolCheckView latest(UUID modelId) {
    requireOpen();
    LlmModel model = models.getModel(modelId);
    repository.abortStale(STALE_SECONDS);
    return repository
        .findFirstByModelIdOrderByStartedAtDesc(modelId)
        .map(check -> view(check, model))
        .orElseThrow(() -> new NotFoundException("Für dieses Modell gibt es keine Prüfung."));
  }

  /** The Markdown report of a completed check of {@code modelId}. */
  @Transactional(readOnly = true)
  public String reportMarkdown(UUID modelId, UUID checkId) {
    requireOpen();
    return repository
        .findById(checkId)
        .filter(check -> check.getModelId().equals(modelId))
        .map(ToolCheck::getReportMarkdown)
        .filter(Objects::nonNull)
        .orElseThrow(() -> new NotFoundException("Diesen Prüfbericht gibt es nicht."));
  }

  private void requireOpen() {
    if (!gate.open()) {
      throw new NotFoundException("Werkzeugprüfungen gibt es nur bei eingeschalteten Agenten.");
    }
  }

  private static ToolCheckView view(ToolCheck check, LlmModel model) {
    ToolCallingReport report =
        check.getReport() == null
            ? null
            : JSON.readValue(check.getReport(), ToolCallingReport.class);
    boolean current =
        check.getBaseUrl().equals(model.getBaseUrl())
            && check.getModelIdentifier().equals(model.getModelIdentifier());
    return new ToolCheckView(check, report, current);
  }
}
