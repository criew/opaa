package io.opaa.group.sync;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.DirectorySyncOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Directory synchronisation as a rights event (#237): the public entry point and the boundary to an
 * actual directory. Deliberately holds no {@code @Transactional} annotation anywhere in this class
 * - {@link DirectoryClient#fetchGroups} runs here, outside any transaction of the application's
 * transaction manager, so a slow or failing real directory connector never holds a work transaction
 * open for the duration of a network call (review of PR #297). The transactional plan computation
 * and, if applicable, application live in {@link DirectorySyncPlanExecutor}, a separate bean called
 * from here through Spring's proxy.
 *
 * <p><b>One run per organization at a time.</b> Every entry point runs under {@link
 * DirectorySyncRunLock}, which holds an advisory lock keyed on {@code organizationId} for the whole
 * of {@link #execute} - the directory fetch included, so a second caller cannot slip past while the
 * first is still reading the directory. A second run of the same organization is rejected with a
 * {@link io.opaa.common.ConflictException} rather than queued behind the first; runs of different
 * organizations never wait for each other. {@link #dryRun} takes the same lock as {@link #run}: it
 * writes the same status row, and a plan computed while another run is applying describes a state
 * that no longer holds.
 *
 * <p><b>Remaining window: an admin edit between fetch and apply.</b> The lock covers concurrent
 * synchronisation runs, not concurrent admin activity. A change made through the admin UI while a
 * run is in flight - e.g. an operator adding someone to an {@code AD_HOC} group, or (once #208
 * exists) a curator action on an {@code ORG_UNIT} group - is not part of the snapshot this run
 * diffs against and can be reverted by it.
 */
@Service
public class DirectorySyncService {

  private static final Logger log = LoggerFactory.getLogger(DirectorySyncService.class);

  /** Mirrors {@link DirectorySyncPlanExecutor}'s identical constant - see its Javadoc. */
  private static final String DIRECTORY_SYNC_ACTOR = "directory-sync";

  private final DirectoryClient directoryClient;
  private final DirectorySyncPlanExecutor planExecutor;
  private final DirectorySyncStatusRecorder statusRecorder;
  private final DirectorySyncStatusRepository statusRepository;
  private final DirectorySyncProperties properties;
  private final AuditEventRecorder auditEventRecorder;
  private final DirectorySyncRunLock runLock;

  public DirectorySyncService(
      DirectoryClient directoryClient,
      DirectorySyncPlanExecutor planExecutor,
      DirectorySyncStatusRecorder statusRecorder,
      DirectorySyncStatusRepository statusRepository,
      DirectorySyncProperties properties,
      AuditEventRecorder auditEventRecorder,
      DirectorySyncRunLock runLock) {
    this.directoryClient = directoryClient;
    this.planExecutor = planExecutor;
    this.statusRecorder = statusRecorder;
    this.statusRepository = statusRepository;
    this.properties = properties;
    this.auditEventRecorder = auditEventRecorder;
    this.runLock = runLock;
  }

  /**
   * Computes the diff against the directory's current state. Never writes group/membership data.
   */
  public SyncReport dryRun(UUID organizationId) {
    return runLock.runExclusively(organizationId, () -> execute(organizationId, false));
  }

  /**
   * Computes the diff and applies it if - and only if - the directory was reachable, did not return
   * an implausibly empty group list, and the fraction of memberships at risk does not exceed {@link
   * DirectorySyncProperties#changeThresholdFraction()}. Otherwise behaves like {@link #dryRun} and
   * reports why nothing was written.
   */
  public SyncReport run(UUID organizationId) {
    return runLock.runExclusively(organizationId, () -> execute(organizationId, true));
  }

  /** The organization's most recent run, empty if it has never run one. */
  public Optional<DirectorySyncStatus> getStatus(UUID organizationId) {
    return statusRepository.findByOrganizationId(organizationId);
  }

  private SyncReport execute(UUID organizationId, boolean applyIfPlausible) {
    Instant now = Instant.now();
    DirectorySnapshot snapshot;
    try {
      snapshot = directoryClient.fetchGroups(organizationId);
    } catch (DirectoryUnavailableException e) {
      String message = "Verzeichnis nicht erreichbar. Der letzte bekannte Stand bleibt in Kraft.";
      log.warn(
          "Directory sync: directory unreachable for organization {}: {}",
          organizationId,
          e.getMessage());
      recordStatusSafely(organizationId, now, DirectorySyncOutcome.UNREACHABLE, message, 0.0);
      // #392 code review, nit 2: this branch returns before DirectorySyncPlanExecutor is ever
      // called, so its own header entry (finish()) never runs for an unreachable directory -
      // "Kopfeintrag des Laufs mit Ergebnis" is otherwise not written for this outcome at all. No
      // ambient transaction is open here (this class deliberately holds none - see the class
      // Javadoc), so this call commits immediately on its own, the same as any plain repository
      // call outside a transaction.
      UUID correlationRef = UUID.randomUUID();
      auditEventRecorder.recordSystemProcessAction(
          AuditEvent.builder()
              .organizationId(organizationId)
              .actorRef(DIRECTORY_SYNC_ACTOR)
              .type(AuditEventType.DIRECTORY_SYNC_RUN_COMPLETED)
              .object(
                  AuditObjectType.DIRECTORY_SYNC_RUN,
                  correlationRef,
                  "Verzeichnisabgleich " + correlationRef)
              .after(Map.of("outcome", DirectorySyncOutcome.UNREACHABLE.name()))
              .outcome(AuditOutcome.FAILURE)
              .reason(message)
              .correlationRef(correlationRef.toString())
              .build());
      return new SyncReport(
          DirectorySyncOutcome.UNREACHABLE,
          now,
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          0,
          0,
          0,
          0.0,
          properties.changeThresholdFraction(),
          message);
    }

    // If planAndApply's transaction fails to commit (e.g. a directory-supplied value that
    // violates a column constraint), the exception propagates from here and nothing below runs -
    // so a failed apply can never be recorded as APPLIED. See DirectorySyncPlanExecutor's class
    // javadoc for the defect this replaced (review of PR #297).
    SyncReport report =
        applyIfPlausible
            ? planExecutor.planAndApply(organizationId, now, snapshot)
            : planExecutor.planOnly(organizationId, now, snapshot);
    recordStatusSafely(
        organizationId, now, report.outcome(), report.message(), report.changedFraction());
    return report;
  }

  /**
   * A failure here (e.g. the status row's own insert/update failing) must not turn an already
   * successful, already-committed plan/apply - or an already-built unreachable report - into an
   * error response: the group/membership changes (if any) are real regardless, and the caller still
   * needs the report. Under-recording the status is the safer direction of the two failure modes
   * (review of PR #297): a missing or stale status line is visible and prompts an operator to
   * check, whereas swallowing the report behind an exception here would additionally invite a retry
   * of a run that already applied.
   */
  private void recordStatusSafely(
      UUID organizationId,
      Instant now,
      DirectorySyncOutcome outcome,
      String message,
      double changedFraction) {
    try {
      statusRecorder.record(organizationId, now, outcome, message, changedFraction);
    } catch (RuntimeException e) {
      log.error(
          "Directory sync: failed to record the outcome ({}) for organization {} - the run itself"
              + " completed and its report is still returned, but the status table may now be"
              + " stale",
          outcome,
          organizationId,
          e);
    }
  }
}
