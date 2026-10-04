package io.opaa.connection.profile;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.NotificationType;
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
 * Tells the managers of each library on a profile - the owner and every holder of {@code MANAGER}
 * or more, groups resolved to their members - that a change of the profile made its next run a full
 * one (ADR-0019), in the transaction of the change.
 */
@Component
class ProfileChangeNotices {

  private final AssetGrantRepository grants;
  private final GroupMembershipResolver groups;
  private final NotificationService notifications;
  private final Clock clock;

  ProfileChangeNotices(
      AssetGrantRepository grants,
      GroupMembershipResolver groups,
      NotificationService notifications,
      Clock clock) {
    this.grants = grants;
    this.groups = groups;
    this.notifications = notifications;
    this.clock = clock;
  }

  /** Notifies the managers of {@code libraries} that {@code label} of {@code profile} changed. */
  void fullSyncForced(
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
                + "“ geändert. Der Abgleichsstand der Bibliothek wurde verworfen; der nächste"
                + " Lauf liest die Quelle vollständig neu.");
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
