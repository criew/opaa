package io.opaa.group.sync;

import io.opaa.audit.AuditEventRecorder;
import io.opaa.audit.AuditEventType;
import io.opaa.audit.AuditObjectType;
import io.opaa.audit.AuditOutcome;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Directory synchronisation as a rights event (#237): the public entry point and the boundary to an
 * actual directory. Deliberately holds no {@code @Transactional} annotation anywhere in this class
 * - {@link DirectoryClient#fetchGroups} runs here, before any database transaction is opened, so a
 * slow or failing real directory connector never holds a database connection or transaction open
 * for the duration of a network call (review of PR #297). The transactional plan computation and,
 * if applicable, application live in {@link DirectorySyncPlanExecutor}, a separate bean called from
 * here through Spring's proxy.
 *
 * <p><b>Known gap: concurrent runs are not serialised, and the fetch-to-apply window is real.</b>
 * Moving the directory fetch outside any transaction (above) widens the time between reading the
 * directory and applying the diff; a change made through the admin UI in that window - e.g. an
 * operator adding someone to an {@code AD_HOC} group, or (once #208 exists) a curator action on an
 * {@code ORG_UNIT} group - is not part of the snapshot this run diffs against and can be reverted
 * by it. Likewise, nothing here stops two calls to {@link #run} for the same organization from
 * overlapping. Neither is new to this change - the previous, single-transaction version had a
 * narrower but non-zero version of the same window - but the window is now large enough to be worth
 * naming rather than assuming away. Out of scope for #237: closing it needs either serialising runs
 * per organization (e.g. a Postgres advisory lock keyed on {@code organizationId}, held for the
 * whole {@link #execute}) or accepting last-writer-wins and documenting it as a deployment
 * constraint (run synchronisation on a schedule, never concurrently, never overlapping an admin
 * bulk-edit window).
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

  public DirectorySyncService(
      DirectoryClient directoryClient,
      DirectorySyncPlanExecutor planExecutor,
      DirectorySyncStatusRecorder statusRecorder,
      DirectorySyncStatusRepository statusRepository,
      DirectorySyncProperties properties,
      AuditEventRecorder auditEventRecorder) {
    this.directoryClient = directoryClient;
    this.planExecutor = planExecutor;
    this.statusRecorder = statusRecorder;
    this.statusRepository = statusRepository;
    this.properties = properties;
    this.auditEventRecorder = auditEventRecorder;
  }

  /**
   * Computes the diff against the directory's current state. Never writes group/membership data.
   */
  public SyncReport dryRun(UUID organizationId) {
    return execute(organizationId, false);
  }

  /**
   * Computes the diff and applies it if - and only if - the directory was reachable, did not return
   * an implausibly empty group list, and the fraction of memberships at risk does not exceed {@link
   * DirectorySyncProperties#changeThresholdFraction()}. Otherwise behaves like {@link #dryRun} and
   * reports why nothing was written.
   */
  public SyncReport run(UUID organizationId) {
    return execute(organizationId, true);
  }

  /** The organization's most recent run, or {@code null} if it has never run one. */
  public DirectorySyncStatus getStatus(UUID organizationId) {
    return statusRepository.findByOrganizationId(organizationId).orElse(null);
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
          organizationId,
          DIRECTORY_SYNC_ACTOR,
          AuditEventType.DIRECTORY_SYNC_RUN_COMPLETED,
          AuditObjectType.DIRECTORY_SYNC_RUN,
          correlationRef,
          "Verzeichnisabgleich " + correlationRef,
          null,
          null,
          null,
          Map.of("outcome", DirectorySyncOutcome.UNREACHABLE.name()),
          AuditOutcome.FAILURE,
          message,
          correlationRef.toString());
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
