package io.opaa.group.sync;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.DirectorySyncOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRepository;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Directory synchronisation as a rights event (#237, #1816): the public entry point and the
 * boundary to an actual directory. Deliberately holds no {@code @Transactional} annotation anywhere
 * in this class - {@link DirectoryClient#fetchGroups} runs here, outside any transaction of the
 * application's transaction manager, so a slow or failing real directory connector never holds a
 * work transaction open for the duration of a network call (review of PR #297). The transactional
 * plan computation and, if applicable, application live in {@link DirectorySyncPlanExecutor}, a
 * separate bean called from here through Spring's proxy.
 *
 * <p><b>Bound to an identity provider row</b> (ADR-0036, Entscheidung 2 and 3). A run exists only
 * for a provider whose row is enabled and whose directory run is switched on; both are refused with
 * a {@link ConflictException} rather than silently doing nothing, so a caller learns why. The
 * {@code dev} mode has no provider row and therefore no synchronisation at all - #1816 decides that
 * open point of ADR-0036, Entscheidung 11 against a synthetic row: every row of {@code
 * oidc_providers} is a trust anchor of the sign-in, and adding one that authenticates nobody in
 * order to hold two settings columns would be the wrong price. A developer who wants to exercise
 * the run locally creates an ordinary provider row and switches the run on there; nothing about the
 * run depends on the operating mode any more.
 *
 * <p><b>One run per provider at a time.</b> Every entry point runs under {@link
 * DirectorySyncRunLock}, which holds an advisory lock keyed on the provider for the whole of {@link
 * #execute} - the directory fetch included, so a second caller cannot slip past while the first is
 * still reading the directory. A second run of the same provider is rejected with a {@link
 * ConflictException} rather than queued behind the first; runs of different providers never wait
 * for each other. {@link #dryRun} takes the same lock as {@link #run}: it writes the same status
 * row, and a plan computed while another run is applying describes a state that no longer holds.
 *
 * <p><b>Remaining window: an admin edit between fetch and apply.</b> The lock covers concurrent
 * synchronisation runs, not concurrent admin activity. A change made through the admin UI while a
 * run is in flight - e.g. an operator adding someone to an {@code AD_HOC} group - is not part of
 * the snapshot this run diffs against and can be reverted by it.
 */
@Service
public class DirectorySyncService {

  private static final Logger log = LoggerFactory.getLogger(DirectorySyncService.class);

  /** Mirrors {@link DirectorySyncPlanExecutor}'s identical constant - see its Javadoc. */
  private static final String DIRECTORY_SYNC_ACTOR = "directory-sync";

  /** The 409 code when the provider's directory run is switched off. */
  public static final String NOT_ENABLED_CODE = "DIRECTORY_SYNC_NOT_ENABLED";

  /** The 409 code when the provider row itself is disabled - its run pauses (ADR-0036/2). */
  public static final String PROVIDER_DISABLED_CODE = "DIRECTORY_SYNC_PROVIDER_DISABLED";

  /** The 409 code when the confirmed plan no longer matches a fresh snapshot (ADR-0036/3). */
  public static final String PLAN_CHANGED_CODE = "DIRECTORY_SYNC_PLAN_CHANGED";

  private final DirectoryClient directoryClient;
  private final DirectorySyncPlanExecutor planExecutor;
  private final DirectorySyncStatusRecorder statusRecorder;
  private final DirectorySyncStatusRepository statusRepository;
  private final DirectorySyncPendingPlanRepository pendingPlanRepository;
  private final OidcProviderRepository providerRepository;
  private final DirectorySyncProperties properties;
  private final AuditEventRecorder auditEventRecorder;
  private final DirectorySyncRunLock runLock;

  public DirectorySyncService(
      DirectoryClient directoryClient,
      DirectorySyncPlanExecutor planExecutor,
      DirectorySyncStatusRecorder statusRecorder,
      DirectorySyncStatusRepository statusRepository,
      DirectorySyncPendingPlanRepository pendingPlanRepository,
      OidcProviderRepository providerRepository,
      DirectorySyncProperties properties,
      AuditEventRecorder auditEventRecorder,
      DirectorySyncRunLock runLock) {
    this.directoryClient = directoryClient;
    this.planExecutor = planExecutor;
    this.statusRecorder = statusRecorder;
    this.statusRepository = statusRepository;
    this.pendingPlanRepository = pendingPlanRepository;
    this.providerRepository = providerRepository;
    this.properties = properties;
    this.auditEventRecorder = auditEventRecorder;
    this.runLock = runLock;
  }

  /**
   * Computes the diff against the provider's current directory state. Never writes group/membership
   * data and never leaves a pending plan behind, not even above the threshold.
   */
  public SyncReport dryRun(UUID organizationId, UUID providerId) {
    SyncTarget target = requireRunnable(organizationId, providerId);
    return runLock.runExclusively(
        providerId,
        () -> execute(target, (snapshot, now) -> planExecutor.planOnly(target, now, snapshot)));
  }

  /**
   * Computes the diff and applies it if - and only if - the directory was reachable, did not return
   * an implausibly empty group list, and the fraction of memberships at risk does not exceed {@link
   * DirectorySyncProperties#changeThresholdFraction()}. Above the threshold nothing is written and
   * the plan is left pending for a decision, replacing whatever this provider had pending.
   */
  public SyncReport run(UUID organizationId, UUID providerId) {
    SyncTarget target = requireRunnable(organizationId, providerId);
    return runExclusivelyApplying(target);
  }

  /**
   * The schedule's entry point: the caller has already established that this provider is enabled
   * and due, so the state checks of {@link #run} would only repeat its own query. A second run
   * already in flight is not an error here - the tick simply skips it.
   */
  SyncReport runScheduled(SyncTarget target) {
    return runExclusivelyApplying(target);
  }

  private SyncReport runExclusivelyApplying(SyncTarget target) {
    return runLock.runExclusively(
        target.providerId(),
        () -> execute(target, (snapshot, now) -> planExecutor.planAndApply(target, now, snapshot)));
  }

  /**
   * Applies a pending plan against a snapshot read now. The plan is recomputed, not replayed: a
   * result that differs from what was shown is stored as the new pending plan and refused with
   * {@link #PLAN_CHANGED_CODE}, so nobody confirms a diff they never saw (ADR-0036, Entscheidung
   * 3). An unreachable directory keeps the plan and reports {@code UNREACHABLE}.
   *
   * @throws NotFoundException if {@code planId} is not this provider's pending plan any more - a
   *     later run replaced it
   */
  public SyncReport confirmPlan(
      UUID organizationId, UUID providerId, UUID planId, UUID actorUserId, String reason) {
    SyncTarget target = requireRunnable(organizationId, providerId);
    return runLock.runExclusively(
        providerId,
        () -> {
          DirectorySyncPendingPlan plan = requirePendingPlan(organizationId, providerId, planId);
          SyncReport report =
              execute(
                  target,
                  (snapshot, now) ->
                      planExecutor.confirmPlan(target, now, snapshot, plan, actorUserId, reason));
          if (report.outcome() == DirectorySyncOutcome.PENDING_CONFIRMATION) {
            throw new ConflictException(report.message(), PLAN_CHANGED_CODE);
          }
          return report;
        });
  }

  /**
   * Drops a pending plan without applying anything. The next run computes the diff afresh and may
   * present it again: discarding decides about this plan, it does not suppress the finding.
   */
  public void discardPlan(
      UUID organizationId, UUID providerId, UUID planId, UUID actorUserId, String reason) {
    runLock.runExclusively(
        providerId,
        () -> {
          DirectorySyncPendingPlan plan = requirePendingPlan(organizationId, providerId, planId);
          // Under the same lock a run takes: otherwise a run finishing right now could replace
          // this plan between the read above and the delete, and the entry would name a decision
          // about a plan that no longer existed.
          planExecutor.discardPlan(plan, actorUserId, reason);
          return null;
        });
  }

  /** The provider's pending plan with the report exactly as it was presented, if it has one. */
  public Optional<PendingPlanView> getPendingPlan(UUID organizationId, UUID providerId) {
    return pendingPlanRepository
        .findByOrganizationIdAndProviderId(organizationId, providerId)
        .map(
            plan ->
                new PendingPlanView(
                    plan.getId(),
                    plan.getProviderId(),
                    plan.getCreatedAt(),
                    plan.getChangedFraction(),
                    plan.getMembershipsRemoved(),
                    DirectorySyncReportCodec.read(plan.getReport())));
  }

  /**
   * One status line per provider that has the directory run switched on or has ever run one - the
   * management overview. A provider whose run was switched off again keeps its line as long as its
   * last run is on record; that is what makes "this used to be synchronised" visible at all.
   */
  public List<DirectorySyncStatusView> listStatus(UUID organizationId) {
    Map<UUID, DirectorySyncStatus> statusByProvider = new HashMap<>();
    statusRepository
        .findByOrganizationId(organizationId)
        .forEach(status -> statusByProvider.put(status.getProviderId(), status));
    Map<UUID, DirectorySyncPendingPlan> planByProvider = new HashMap<>();
    pendingPlanRepository
        .findByOrganizationId(organizationId)
        .forEach(plan -> planByProvider.put(plan.getProviderId(), plan));
    List<DirectorySyncStatusView> views = new ArrayList<>();
    for (OidcProvider provider : providerRepository.findAllByOrderBySortOrderAscDisplayNameAsc()) {
      DirectorySyncStatus status = statusByProvider.get(provider.getId());
      if (!provider.isDirectorySyncEnabled() && status == null) {
        continue;
      }
      views.add(
          new DirectorySyncStatusView(
              provider.getId(),
              provider.getDisplayName(),
              provider.isEnabled(),
              provider.isDirectorySyncEnabled(),
              provider.getDirectorySyncIntervalMinutes(),
              status,
              planByProvider.get(provider.getId())));
    }
    return views;
  }

  /**
   * The provider a run is bound to, refusing every state in which a run must not happen: an unknown
   * provider, a disabled row (no anchor of trust, so its run pauses - ADR-0036/2) and a provider
   * whose run is simply switched off.
   */
  private SyncTarget requireRunnable(UUID organizationId, UUID providerId) {
    OidcProvider provider =
        providerRepository
            .findById(providerId)
            .orElseThrow(() -> new NotFoundException("Anbieter nicht gefunden: " + providerId));
    if (!provider.isDirectorySyncEnabled()) {
      throw new ConflictException(
          "Für diesen Anbieter ist der Verzeichnisabgleich nicht eingeschaltet.", NOT_ENABLED_CODE);
    }
    if (!provider.isEnabled()) {
      throw new ConflictException(
          "Der Anbieter ist deaktiviert. Sein Verzeichnisabgleich pausiert, solange er es ist.",
          PROVIDER_DISABLED_CODE);
    }
    return toTarget(organizationId, provider);
  }

  static SyncTarget toTarget(UUID organizationId, OidcProvider provider) {
    return new SyncTarget(
        organizationId, provider.getId(), provider.getIssuerUri(), provider.getDisplayName());
  }

  private DirectorySyncPendingPlan requirePendingPlan(
      UUID organizationId, UUID providerId, UUID planId) {
    return pendingPlanRepository
        .findByOrganizationIdAndProviderId(organizationId, providerId)
        .filter(plan -> plan.getId().equals(planId))
        .orElseThrow(
            () ->
                new NotFoundException(
                    "Dieser Plan liegt nicht mehr zur Entscheidung vor. Ein neuer Lauf hat ihn"
                        + " ersetzt oder er wurde bereits entschieden."));
  }

  /**
   * Reads the provider's directory and hands the snapshot to {@code plan}. An unreachable directory
   * never reaches {@link DirectorySyncPlanExecutor} at all, so the header entry of such a run is
   * written here - "Kopfeintrag des Laufs mit Ergebnis" is otherwise not written for this outcome.
   * No ambient transaction is open here (this class deliberately holds none - see the class
   * Javadoc), so that call commits immediately on its own.
   */
  private SyncReport execute(
      SyncTarget target, BiFunction<DirectorySnapshot, Instant, SyncReport> plan) {
    Instant now = Instant.now();
    DirectorySnapshot snapshot;
    try {
      snapshot = directoryClient.fetchGroups(target.organizationId(), target.providerId());
    } catch (DirectoryUnavailableException e) {
      return recordUnreachable(target, now, e);
    }

    // If the executor's transaction fails to commit (e.g. a directory-supplied value that violates
    // a column constraint), the exception propagates from here and nothing below runs - so a failed
    // apply can never be recorded as APPLIED. See DirectorySyncPlanExecutor's class javadoc for the
    // defect this replaced (review of PR #297).
    SyncReport report = plan.apply(snapshot, now);
    recordStatusSafely(target, now, report.outcome(), report.message(), report.changedFraction());
    return report;
  }

  private SyncReport recordUnreachable(
      SyncTarget target, Instant now, DirectoryUnavailableException cause) {
    String message = "Verzeichnis nicht erreichbar. Der letzte bekannte Stand bleibt in Kraft.";
    log.warn(
        "Directory sync: directory unreachable for provider {} of organization {}: {}",
        target.providerId(),
        target.organizationId(),
        cause.getMessage());
    recordStatusSafely(target, now, DirectorySyncOutcome.UNREACHABLE, message, 0.0);
    UUID correlationRef = UUID.randomUUID();
    Map<String, Object> after = new HashMap<>();
    after.put("outcome", DirectorySyncOutcome.UNREACHABLE.name());
    after.put("providerId", target.providerId().toString());
    auditEventRecorder.recordSystemProcessAction(
        AuditEvent.builder()
            .organizationId(target.organizationId())
            .actorRef(DIRECTORY_SYNC_ACTOR)
            .type(AuditEventType.DIRECTORY_SYNC_RUN_COMPLETED)
            .object(
                AuditObjectType.DIRECTORY_SYNC_RUN,
                correlationRef,
                "Verzeichnisabgleich " + correlationRef + " (" + target.displayName() + ")")
            .after(after)
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
        List.of(),
        0,
        0,
        0,
        0.0,
        properties.changeThresholdFraction(),
        message);
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
      SyncTarget target,
      Instant now,
      DirectorySyncOutcome outcome,
      String message,
      double changedFraction) {
    try {
      statusRecorder.record(
          target.organizationId(), target.providerId(), now, outcome, message, changedFraction);
    } catch (RuntimeException e) {
      log.error(
          "Directory sync: failed to record the outcome ({}) for provider {} - the run itself"
              + " completed and its report is still returned, but the status table may now be"
              + " stale",
          outcome,
          target.providerId(),
          e);
    }
  }
}
