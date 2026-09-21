package io.opaa.library;

import io.opaa.api.types.LibraryOwnerType;
import io.opaa.api.types.SuccessionAddressee;
import io.opaa.api.types.SuccessionKind;
import io.opaa.api.types.SuccessionObjectType;
import io.opaa.auth.AccountActivityService;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.permission.GroupCapabilityService;
import io.opaa.permission.GroupMembershipResolver;
import io.opaa.permission.GroupSubject;
import io.opaa.permission.GroupSubjectDirectory;
import io.opaa.permission.SuccessionFinding;
import io.opaa.permission.SuccessionFindingSource;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A library without a capable owner (#1819, ADR-0036 Entscheidung 6): a person whose account is no
 * longer active, or a group that is dissolved, switched off or has lost its last active account.
 *
 * <p>The addressee follows the object, not the finder: a library of an internal group is the
 * business of that group's stewards, everything else of the system administration.
 */
@Component
class LibrarySuccessionSource implements SuccessionFindingSource {

  private final KnowledgeLibraryRepository libraries;
  private final AccountActivityService accountActivity;
  private final GroupSubjectDirectory groupDirectory;
  private final GroupCapabilityService groupCapability;
  private final GroupMembershipResolver membershipResolver;
  private final UserRepository users;

  LibrarySuccessionSource(
      KnowledgeLibraryRepository libraries,
      AccountActivityService accountActivity,
      GroupSubjectDirectory groupDirectory,
      GroupCapabilityService groupCapability,
      GroupMembershipResolver membershipResolver,
      UserRepository users) {
    this.libraries = libraries;
    this.accountActivity = accountActivity;
    this.groupDirectory = groupDirectory;
    this.groupCapability = groupCapability;
    this.membershipResolver = membershipResolver;
    this.users = users;
  }

  @Override
  public SuccessionKind kind() {
    return SuccessionKind.OPEN_SUCCESSION;
  }

  @Override
  public SuccessionObjectType objectType() {
    return SuccessionObjectType.KNOWLEDGE_LIBRARY;
  }

  @Override
  public List<SuccessionFinding> findingsOf(UUID organizationId) {
    return List.copyOf(
        findingsAmong(libraries.findByOrganizationId(organizationId), true).values());
  }

  /**
   * The state of a whole list of libraries, with <b>one</b> account query for all their person
   * owners - the overview carries the same marking as the detail view (ADR-0036, Entscheidung 6),
   * and one lookup per row would put it on every page of every reader.
   *
   * @param withMembershipHints only the operational list shows them; the marking at an object is
   *     state and addressee alone, so the reader's overview pays nothing for them
   */
  Map<UUID, SuccessionFinding> findingsAmong(
      Collection<KnowledgeLibrary> candidates, boolean withMembershipHints) {
    Set<UUID> activeOwners =
        accountActivity.activeAmong(
            candidates.stream()
                .filter(library -> library.getOwnerType() == LibraryOwnerType.USER)
                .map(KnowledgeLibrary::getOwnerUserId)
                .toList());
    Map<UUID, SuccessionFinding> findings = new LinkedHashMap<>();
    for (KnowledgeLibrary library : candidates) {
      if (library.getOwnerType() == LibraryOwnerType.USER) {
        if (!activeOwners.contains(library.getOwnerUserId())) {
          findings.put(library.getId(), findingFor(library, null, withMembershipHints));
        }
        continue;
      }
      GroupSubject owner = groupDirectory.find(library.getOwnerGroupId()).orElse(null);
      if (owner == null || !groupCapability.isCapable(owner)) {
        findings.put(library.getId(), findingFor(library, owner, withMembershipHints));
      }
    }
    return findings;
  }

  @Override
  public Optional<SuccessionFinding> findingFor(UUID libraryId) {
    return libraries
        .findById(libraryId)
        .filter(this::withoutCapableOwner)
        .map(
            library ->
                findingFor(
                    library,
                    library.getOwnerType() == LibraryOwnerType.GROUP
                        ? groupDirectory.find(library.getOwnerGroupId()).orElse(null)
                        : null,
                    true));
  }

  private boolean withoutCapableOwner(KnowledgeLibrary library) {
    if (library.getOwnerType() == LibraryOwnerType.USER) {
      return accountActivity.activeAmong(List.of(library.getOwnerUserId())).isEmpty();
    }
    return !groupCapability.isCapable(library.getOwnerGroupId());
  }

  /**
   * The owner is named for the administration's orientation, never as a query axis - and a
   * protected group is named by its protection alone (ADR-0036, Entscheidung 9).
   */
  private SuccessionFinding findingFor(
      KnowledgeLibrary library, GroupSubject ownerGroup, boolean withMembershipHints) {
    boolean internalGroupOwner = ownerGroup != null && ownerGroup.internal();
    SuccessionFinding finding =
        SuccessionFinding.of(
            SuccessionObjectType.KNOWLEDGE_LIBRARY,
            library.getId(),
            library.getName(),
            internalGroupOwner
                ? SuccessionAddressee.GROUP_STEWARDS
                : SuccessionAddressee.SYSTEM_ADMINISTRATION);
    if (ownerGroup != null) {
      return finding.withOwnerHint(
          ownerGroup.protectedGroup() ? "Geschützte Gruppe" : ownerGroup.name());
    }
    return finding
        .withOwnerHint(
            users
                .findById(library.getOwnerUserId())
                .map(LibrarySuccessionSource::nameOf)
                .orElse(null))
        .withMembershipHints(
            withMembershipHints ? membershipHints(library.getOwnerUserId()) : List.of());
  }

  /**
   * "War Mitglied von Referat 50" (Personalrat E4): a suggestion where to look for a successor,
   * Bestandsinformation and nothing else - it is never counted, sorted or queried, and a protected
   * group appears by its protection alone (ADR-0036, Entscheidung 9).
   */
  private List<String> membershipHints(UUID ownerUserId) {
    return membershipResolver.groupIdsForUser(ownerUserId).stream()
        .map(groupDirectory::find)
        .flatMap(Optional::stream)
        .filter(group -> !group.dissolved())
        .map(group -> group.protectedGroup() ? "Geschützte Gruppe" : group.name())
        .filter(Objects::nonNull)
        .sorted()
        .toList();
  }

  private static String nameOf(User user) {
    return user.getDisplayName() != null ? user.getDisplayName() : user.getEmail();
  }
}
