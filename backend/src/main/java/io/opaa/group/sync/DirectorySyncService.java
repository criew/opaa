package io.opaa.group.sync;

import io.opaa.api.dto.DirectorySyncReportResponse;
import io.opaa.api.dto.DirectorySyncStatusResponse;
import java.time.Instant;
import java.util.List;
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
 */
@Service
public class DirectorySyncService {

  private static final Logger log = LoggerFactory.getLogger(DirectorySyncService.class);

  private final DirectoryClient directoryClient;
  private final DirectorySyncPlanExecutor planExecutor;
  private final DirectorySyncStatusRecorder statusRecorder;
  private final DirectorySyncStatusRepository statusRepository;
  private final DirectorySyncProperties properties;

  public DirectorySyncService(
      DirectoryClient directoryClient,
      DirectorySyncPlanExecutor planExecutor,
      DirectorySyncStatusRecorder statusRecorder,
      DirectorySyncStatusRepository statusRepository,
      DirectorySyncProperties properties) {
    this.directoryClient = directoryClient;
    this.planExecutor = planExecutor;
    this.statusRecorder = statusRecorder;
    this.statusRepository = statusRepository;
    this.properties = properties;
  }

  /**
   * Computes the diff against the directory's current state. Never writes group/membership data.
   */
  public DirectorySyncReportResponse dryRun(UUID organizationId) {
    return execute(organizationId, false);
  }

  /**
   * Computes the diff and applies it if - and only if - the directory was reachable, did not return
   * an implausibly empty group list, and the fraction of memberships at risk does not exceed {@link
   * DirectorySyncProperties#changeThresholdFraction()}. Otherwise behaves like {@link #dryRun} and
   * reports why nothing was written.
   */
  public DirectorySyncReportResponse run(UUID organizationId) {
    return execute(organizationId, true);
  }

  public DirectorySyncStatusResponse getStatus(UUID organizationId) {
    return statusRepository
        .findByOrganizationId(organizationId)
        .map(
            status ->
                new DirectorySyncStatusResponse()
                    .lastRunAt(status.getLastRunAt())
                    .lastOutcome(status.getLastOutcome())
                    .lastMessage(status.getLastMessage())
                    .lastAppliedAt(status.getLastAppliedAt())
                    .lastChangedFraction(status.getLastChangedFraction()))
        .orElseGet(DirectorySyncStatusResponse::new);
  }

  private DirectorySyncReportResponse execute(UUID organizationId, boolean applyIfPlausible) {
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
      statusRecorder.record(organizationId, now, DirectorySyncOutcome.UNREACHABLE, message, 0.0);
      return new DirectorySyncReportResponse(
              DirectorySyncOutcome.UNREACHABLE,
              now,
              List.of(),
              List.of(),
              List.of(),
              List.of(),
              0,
              0,
              0.0,
              properties.changeThresholdFraction(),
              message)
          .unresolvedMemberCount(0);
    }

    return applyIfPlausible
        ? planExecutor.planAndApply(organizationId, now, snapshot)
        : planExecutor.planOnly(organizationId, now, snapshot);
  }
}
