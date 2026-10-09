package io.opaa.connection.profile;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.NotificationType;
import io.opaa.common.ConflictException;
import io.opaa.indexing.job.IndexingJobRepository;
import io.opaa.indexing.job.JobStatus;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.notification.NotificationService;
import io.opaa.permission.AssetGrant;
import io.opaa.permission.AssetGrantRepository;
import io.opaa.permission.GroupMembershipResolver;
import io.opaa.permission.PermissionSubject;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A change of a profile that makes the next run of every library on it a full one: refused while a
 * shared one runs, which would go on fetching as the new account under the old settings. A private
 * one never holds it up; the state its going run writes carries the old settings basis, so the next
 * run discards it ({@code SyncStateBasis}), and the going run removes nothing for being absent.
 * Afterwards the managers of each library - the owner and every holder of {@code MANAGER} or more,
 * groups resolved to their members - are told (ADR-0019), in the transaction of the change.
 */
@Component
class ProfileFullSync {

  /** Refusal of a change that discards the run state of a library while it runs. */
  static final String RUN_IN_PROGRESS = "CONNECTION_PROFILE_RUN_IN_PROGRESS";

  private final IndexingJobRepository jobs;
  private final AssetGrantRepository grants;
  private final GroupMembershipResolver groups;
  private final NotificationService notifications;
  private final Clock clock;

  ProfileFullSync(
      IndexingJobRepository jobs,
      AssetGrantRepository grants,
      GroupMembershipResolver groups,
      NotificationService notifications,
      Clock clock) {
    this.jobs = jobs;
    this.grants = grants;
    this.groups = groups;
    this.notifications = notifications;
    this.clock = clock;
  }

  /**
   * Refuses with 409 {@value #RUN_IN_PROGRESS} while a run of one of {@code libraries} is going.
   */
  void requireNoneRunning(Collection<KnowledgeLibrary> libraries) {
    long running =
        libraries.stream()
            .filter(
                library ->
                    jobs.existsByStatusAndLibraryIdAndOrganizationId(
                        JobStatus.RUNNING, library.getId(), library.getOrganizationId()))
            .count();
    if (running > 0) {
      throw new ConflictException(
          "Für "
              + running
              + (running == 1 ? " Bibliothek" : " Bibliotheken")
              + " auf diesem Zugang läuft gerade eine Indexierung. Die Änderung verwirft ihren"
              + " Abgleichstand; bitte nach dem Ende des Laufs erneut speichern. Bei einem lange"
              + " laufenden Abgleich: den Zugang sperren, das Ende der Läufe abwarten, ändern und"
              + " wieder entsperren.",
          RUN_IN_PROGRESS);
    }
  }

  /** Notifies the managers of {@code libraries} that {@code label} of {@code profile} changed. */
  void notifyManagers(
      ConnectionProfile profile, String label, Collection<KnowledgeLibrary> libraries) {
    for (KnowledgeLibrary library : libraries) {
      for (UUID manager : managersOf(library)) {
        notifications.notify(
            library.getOrganizationId(),
            manager,
            NotificationType.SOURCE_FULL_SYNC_FORCED,
            AuditObjectType.KNOWLEDGE_LIBRARY,
            library.getId(),
            "Vollabgleich der Bibliothek „" + library.getName() + "“",
            "Die Systemverwaltung hat am Zugang „"
                + profile.getName()
                + "“ die Vorgabe „"
                + label
                + "“ geändert. Der Abgleichstand der Bibliothek wurde verworfen; der nächste"
                + " Lauf liest die Quelle vollständig neu. Bis dahin bleiben die bisher"
                + " indexierten Dokumente durchsuchbar.");
      }
    }
  }

  private Set<UUID> managersOf(KnowledgeLibrary library) {
    Set<UUID> managers = new LinkedHashSet<>(groups.resolveUserIds(library.ownerSubject()));
    Instant now = clock.instant();
    for (AssetGrant grant :
        grants.findByAssetTypeAndAssetId(library.getAssetType(), library.getId())) {
      if (grant.isExpired(now) || !grant.getRole().atLeast(AssetRole.MANAGER)) {
        continue;
      }
      switch (grant.getSubjectType()) {
        case USER -> managers.add(grant.getSubjectUserId());
        case GROUP ->
            managers.addAll(
                groups.resolveUserIds(
                    PermissionSubject.group(
                        grant.getSubjectGroupId(), library.getOrganizationId())));
        case ALL_ACCOUNTS -> {}
      }
    }
    return managers;
  }
}
