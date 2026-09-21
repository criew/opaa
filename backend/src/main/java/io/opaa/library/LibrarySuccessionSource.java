package io.opaa.library;

import io.opaa.api.types.LibraryOwnerType;
import io.opaa.api.types.SuccessionAddressee;
import io.opaa.api.types.SuccessionKind;
import io.opaa.api.types.SuccessionObjectType;
import io.opaa.auth.AccountActivityService;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.permission.GroupCapabilityService;
import io.opaa.permission.GroupSubject;
import io.opaa.permission.GroupSubjectDirectory;
import io.opaa.permission.SuccessionFinding;
import io.opaa.permission.SuccessionFindingSource;
import java.util.ArrayList;
import java.util.List;
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
  private final UserRepository users;

  LibrarySuccessionSource(
      KnowledgeLibraryRepository libraries,
      AccountActivityService accountActivity,
      GroupSubjectDirectory groupDirectory,
      GroupCapabilityService groupCapability,
      UserRepository users) {
    this.libraries = libraries;
    this.accountActivity = accountActivity;
    this.groupDirectory = groupDirectory;
    this.groupCapability = groupCapability;
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
    List<KnowledgeLibrary> all = libraries.findByOrganizationId(organizationId);
    Set<UUID> activeOwners =
        accountActivity.activeAmong(
            all.stream()
                .filter(library -> library.getOwnerType() == LibraryOwnerType.USER)
                .map(KnowledgeLibrary::getOwnerUserId)
                .toList());
    List<SuccessionFinding> findings = new ArrayList<>();
    for (KnowledgeLibrary library : all) {
      if (library.getOwnerType() == LibraryOwnerType.USER) {
        if (!activeOwners.contains(library.getOwnerUserId())) {
          findings.add(findingFor(library, null));
        }
        continue;
      }
      GroupSubject owner = groupDirectory.find(library.getOwnerGroupId()).orElse(null);
      if (owner == null || !groupCapability.isCapable(owner)) {
        findings.add(findingFor(library, owner));
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
                        : null));
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
  private SuccessionFinding findingFor(KnowledgeLibrary library, GroupSubject ownerGroup) {
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
    return finding.withOwnerHint(
        users.findById(library.getOwnerUserId()).map(LibrarySuccessionSource::nameOf).orElse(null));
  }

  private static String nameOf(User user) {
    return user.getDisplayName() != null ? user.getDisplayName() : user.getEmail();
  }
}
