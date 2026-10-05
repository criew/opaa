package io.opaa.connection.consent;

import io.opaa.api.types.AssetGrantSubjectType;
import io.opaa.api.types.AssetRole;
import io.opaa.auth.UserRepository;
import io.opaa.common.ValidationException;
import io.opaa.connection.profile.LibraryConnection.Responsible;
import io.opaa.group.Group;
import io.opaa.group.GroupRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.LibraryAccessService;
import io.opaa.permission.AssetGrant;
import io.opaa.permission.AssetGrantRepository;
import io.opaa.permission.GroupMembershipResolver;
import io.opaa.permission.PermissionSubject;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Who answers for a library's own consent: a person or a group holding {@code MANAGER} on the
 * library, so that it can connect anew. Warnings go to the responsible while they still hold it - a
 * group's active members - and otherwise to every manager of the library.
 */
@Component
public class ConsentResponsibles {

  private final LibraryAccessService access;
  private final AssetGrantRepository grants;
  private final GroupMembershipResolver groups;
  private final GroupRepository groupRepository;
  private final UserRepository users;
  private final Clock clock;

  ConsentResponsibles(
      LibraryAccessService access,
      AssetGrantRepository grants,
      GroupMembershipResolver groups,
      GroupRepository groupRepository,
      UserRepository users,
      Clock clock) {
    this.access = access;
    this.grants = grants;
    this.groups = groups;
    this.groupRepository = groupRepository;
    this.users = users;
    this.clock = clock;
  }

  /**
   * Refuses (German 400) {@code responsible} for {@code library} unless it holds {@code MANAGER}
   * there - a person directly or through a group, a group by its own grant or as the owner.
   */
  public void require(KnowledgeLibrary library, Responsible responsible) {
    if (!holdsManager(library, responsible)) {
      throw new ValidationException(
          "Verantwortlich für die Verbindung der Quelle kann nur eine Person oder Gruppe sein, die"
              + " die Bibliothek verwaltet.");
    }
  }

  /** Who is told about {@code library}'s consent: see the class description. */
  public Set<UUID> recipients(KnowledgeLibrary library, Responsible responsible) {
    if (responsible != null && holdsManager(library, responsible)) {
      return switch (responsible.type()) {
        case USER -> Set.of(responsible.id());
        case GROUP ->
            groups.resolveUserIds(
                PermissionSubject.group(responsible.id(), library.getOrganizationId()));
      };
    }
    return managersOf(library);
  }

  /** The name of {@code responsible} as the library's managers see it, {@code null} for none. */
  public String nameOf(Responsible responsible) {
    if (responsible == null) {
      return null;
    }
    return switch (responsible.type()) {
      case USER -> users.findById(responsible.id()).map(user -> user.getDisplayName()).orElse(null);
      case GROUP -> groupRepository.findById(responsible.id()).map(Group::getName).orElse(null);
    };
  }

  private boolean holdsManager(KnowledgeLibrary library, Responsible responsible) {
    return switch (responsible.type()) {
      case USER -> access.canManage(library, responsible.id(), false);
      case GROUP ->
          responsible.id().equals(library.getOwnerGroupId())
              || grants
                  .findByAssetTypeAndAssetIdAndSubjectTypeAndSubjectGroupId(
                      library.getAssetType(),
                      library.getId(),
                      AssetGrantSubjectType.GROUP,
                      responsible.id())
                  .filter(grant -> !grant.isExpired(clock.instant()))
                  .filter(grant -> grant.getRole().atLeast(AssetRole.MANAGER))
                  .isPresent();
    };
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
