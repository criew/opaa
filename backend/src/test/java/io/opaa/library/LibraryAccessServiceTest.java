package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.common.AccessDeniedException;
import io.opaa.common.NotFoundException;
import io.opaa.group.GroupMembershipResolver;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LibraryAccessServiceTest {

  private AssetGrantRepository grantRepository;
  private KnowledgeLibraryRepository libraryRepository;
  private GroupMembershipResolver membershipResolver;
  private LibraryAccessService accessService;

  private final UUID userId = UUID.randomUUID();
  private final UUID organizationId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    grantRepository = mock(AssetGrantRepository.class);
    libraryRepository = mock(KnowledgeLibraryRepository.class);
    membershipResolver = mock(GroupMembershipResolver.class);
    accessService =
        new LibraryAccessService(grantRepository, libraryRepository, membershipResolver);
    when(membershipResolver.groupIdsForUser(userId)).thenReturn(Set.of());
  }

  private KnowledgeLibrary privateUserOwnedLibrary(UUID libraryId) {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            organizationId,
            "Bibliothek",
            null,
            UUID.randomUUID(),
            LibraryVisibility.PRIVATE,
            false);
    setId(library, libraryId);
    return library;
  }

  /**
   * A library owned by someone other than {@link #userId}, with an explicit visibility - used to
   * exercise the fail-closed/opened-by-visibility/opened-by-grant formula independent of ownership
   * (#406, formerly exercised via the now-removed {@code SYSTEM} owner kind - see #521).
   */
  private KnowledgeLibrary libraryWithVisibility(UUID libraryId, LibraryVisibility visibility) {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            organizationId, "Bibliothek", null, UUID.randomUUID(), visibility, false);
    setId(library, libraryId);
    return library;
  }

  private AssetGrant userGrant(UUID libraryId, UUID subjectUserId, AssetRole role) {
    return AssetGrant.forUser(libraryId, organizationId, subjectUserId, role, null, subjectUserId);
  }

  private void setId(KnowledgeLibrary library, UUID id) {
    try {
      var field = KnowledgeLibrary.class.getDeclaredField("id");
      field.setAccessible(true);
      field.set(library, id);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void aPrivateLibraryWithNoGrantsIsClosedToOrdinaryUsersButOpenToASystemAdmin() {
    UUID libraryId = UUID.randomUUID();
    KnowledgeLibrary library = libraryWithVisibility(libraryId, LibraryVisibility.PRIVATE);
    when(grantRepository.findByLibraryId(libraryId)).thenReturn(List.of());

    // The fail-closed default #201 originally asked for the now-removed SYSTEM owner kind (#521);
    // an ordinary PRIVATE, ungranted library follows the same formula (#406) without any special
    // case.
    assertThat(accessService.canRead(library, userId, false)).isFalse();
    assertThat(accessService.canRead(library, userId, true)).isTrue();
  }

  @Test
  void anOrganizationVisibleLibraryIsReadableByAnyoneInTheOrganization() {
    UUID libraryId = UUID.randomUUID();
    KnowledgeLibrary library = libraryWithVisibility(libraryId, LibraryVisibility.ORGANIZATION);
    when(grantRepository.findByLibraryId(libraryId)).thenReturn(List.of());

    assertThat(accessService.canRead(library, userId, false)).isTrue();
  }

  @Test
  void aGrantOnAPrivateLibraryCounts() {
    UUID libraryId = UUID.randomUUID();
    KnowledgeLibrary library = libraryWithVisibility(libraryId, LibraryVisibility.PRIVATE);
    when(grantRepository.findByLibraryId(libraryId))
        .thenReturn(List.of(userGrant(libraryId, userId, AssetRole.VIEWER)));

    assertThat(accessService.canRead(library, userId, false)).isTrue();
  }

  @Test
  void systemAdminBypassesGrantsOnAnOrdinaryLibrary() {
    UUID libraryId = UUID.randomUUID();
    KnowledgeLibrary library = privateUserOwnedLibrary(libraryId);
    when(grantRepository.findByLibraryId(libraryId)).thenReturn(List.of());

    assertThat(accessService.canManage(library, userId, true)).isTrue();
  }

  @Test
  void requireRoleAnswers404ForACallerWithNoAccessAtAll() {
    // #436: no grant, no organization-wide floor - the caller cannot even tell the library exists.
    UUID libraryId = UUID.randomUUID();
    KnowledgeLibrary library = privateUserOwnedLibrary(libraryId);
    when(grantRepository.findByLibraryId(libraryId)).thenReturn(List.of());

    assertThatThrownBy(() -> accessService.requireRole(library, userId, false, AssetRole.VIEWER))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void requireRoleAnswers403ForACallerWithSomeButInsufficientAccess() {
    // #436: a real VIEWER grant exists, but MANAGER is required - the library's existence is
    // already established, so this is the ordinary "not enough" case.
    UUID libraryId = UUID.randomUUID();
    KnowledgeLibrary library = privateUserOwnedLibrary(libraryId);
    AssetGrant grant =
        AssetGrant.forUser(libraryId, organizationId, userId, AssetRole.VIEWER, null, userId);
    when(grantRepository.findByLibraryId(libraryId)).thenReturn(List.of(grant));

    assertThatThrownBy(() -> accessService.requireRole(library, userId, false, AssetRole.MANAGER))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void requireRoleReturnsTheResolvedRoleWhenSufficient() {
    UUID libraryId = UUID.randomUUID();
    KnowledgeLibrary library = privateUserOwnedLibrary(libraryId);
    AssetGrant grant =
        AssetGrant.forUser(libraryId, organizationId, userId, AssetRole.MANAGER, null, userId);
    when(grantRepository.findByLibraryId(libraryId)).thenReturn(List.of(grant));

    assertThat(accessService.requireRole(library, userId, false, AssetRole.VIEWER))
        .isEqualTo(AssetRole.MANAGER);
  }

  @Test
  void requireRoleBypassesToOwnerForASystemAdminEvenWithNoGrantAtAll() {
    // #436: requireRole must fail-open a system admin exactly like effectiveRole already does
    // (see systemAdminBypassesGrantsOnAnOrdinaryLibrary above) - the caller has no grant on this
    // library whatsoever, yet requireRole must neither answer 404 nor 403 for a system admin.
    UUID libraryId = UUID.randomUUID();
    KnowledgeLibrary library = privateUserOwnedLibrary(libraryId);
    when(grantRepository.findByLibraryId(libraryId)).thenReturn(List.of());

    assertThat(accessService.requireRole(library, userId, true, AssetRole.OWNER))
        .isEqualTo(AssetRole.OWNER);
  }

  @Test
  void aUserWithNoGrantAndNoOrganizationWideVisibilityHasNoAccess() {
    UUID libraryId = UUID.randomUUID();
    KnowledgeLibrary library = privateUserOwnedLibrary(libraryId);
    when(grantRepository.findByLibraryId(libraryId)).thenReturn(List.of());

    assertThat(accessService.canRead(library, userId, false)).isFalse();
    assertThat(accessService.canManage(library, userId, false)).isFalse();
    assertThat(accessService.effectiveRole(library, userId, false)).isNull();
  }

  @Test
  void aDirectViewerGrantAllowsReadingButNotManaging() {
    UUID libraryId = UUID.randomUUID();
    KnowledgeLibrary library = privateUserOwnedLibrary(libraryId);
    AssetGrant grant =
        AssetGrant.forUser(libraryId, organizationId, userId, AssetRole.VIEWER, null, userId);
    when(grantRepository.findByLibraryId(libraryId)).thenReturn(List.of(grant));

    assertThat(accessService.canRead(library, userId, false)).isTrue();
    assertThat(accessService.canManage(library, userId, false)).isFalse();
  }

  @Test
  void aDirectManagerGrantAllowsBothReadingAndManaging() {
    UUID libraryId = UUID.randomUUID();
    KnowledgeLibrary library = privateUserOwnedLibrary(libraryId);
    AssetGrant grant =
        AssetGrant.forUser(libraryId, organizationId, userId, AssetRole.MANAGER, null, userId);
    when(grantRepository.findByLibraryId(libraryId)).thenReturn(List.of(grant));

    assertThat(accessService.canRead(library, userId, false)).isTrue();
    assertThat(accessService.canManage(library, userId, false)).isTrue();
  }

  @Test
  void anEditorGrantAllowsEditingButNotManaging() {
    // #420: uploading/deleting documents (io.opaa.library.LibraryDocumentService#requireEditable)
    // requires EDITOR, one level below MANAGER - a VIEWER may read a library's contents but not
    // change them. requireEditable reads effectiveRole directly (not a dedicated canEdit method -
    // it also needs to distinguish "no role at all" from "a role below EDITOR", which a boolean
    // cannot express), so this pins the same threshold at the source.
    UUID libraryId = UUID.randomUUID();
    KnowledgeLibrary library = privateUserOwnedLibrary(libraryId);
    AssetGrant grant =
        AssetGrant.forUser(libraryId, organizationId, userId, AssetRole.EDITOR, null, userId);
    when(grantRepository.findByLibraryId(libraryId)).thenReturn(List.of(grant));

    assertThat(accessService.effectiveRole(library, userId, false).atLeast(AssetRole.EDITOR))
        .isTrue();
    assertThat(accessService.canManage(library, userId, false)).isFalse();
  }

  @Test
  void aViewerGrantDoesNotAllowEditing() {
    UUID libraryId = UUID.randomUUID();
    KnowledgeLibrary library = privateUserOwnedLibrary(libraryId);
    AssetGrant grant =
        AssetGrant.forUser(libraryId, organizationId, userId, AssetRole.VIEWER, null, userId);
    when(grantRepository.findByLibraryId(libraryId)).thenReturn(List.of(grant));

    assertThat(accessService.effectiveRole(library, userId, false).atLeast(AssetRole.EDITOR))
        .isFalse();
  }

  @Test
  void theLowestGrantAllowsReadingButNotManaging() {
    // #330 dropped the USER rank that sat below VIEWER, so the lowest grant that still exists
    // carries read access to the configuration. Replaces aUserOnlyGrantDoesNotAllowReadingConfigu-
    // ration, which asserted exactly the distinction that was removed.
    UUID libraryId = UUID.randomUUID();
    KnowledgeLibrary library = privateUserOwnedLibrary(libraryId);
    AssetGrant grant =
        AssetGrant.forUser(libraryId, organizationId, userId, AssetRole.VIEWER, null, userId);
    when(grantRepository.findByLibraryId(libraryId)).thenReturn(List.of(grant));

    assertThat(accessService.canRead(library, userId, false)).isTrue();
    assertThat(accessService.canManage(library, userId, false)).isFalse();
  }

  @Test
  void anExpiredGrantGrantsNoAccess() {
    UUID libraryId = UUID.randomUUID();
    KnowledgeLibrary library = privateUserOwnedLibrary(libraryId);
    AssetGrant grant =
        AssetGrant.forUser(
            libraryId,
            organizationId,
            userId,
            AssetRole.OWNER,
            Instant.now().minusSeconds(60),
            userId);
    when(grantRepository.findByLibraryId(libraryId)).thenReturn(List.of(grant));

    assertThat(accessService.canRead(library, userId, false)).isFalse();
  }

  @Test
  void aGroupGrantReachesAMemberOfThatGroup() {
    UUID libraryId = UUID.randomUUID();
    UUID groupId = UUID.randomUUID();
    KnowledgeLibrary library = privateUserOwnedLibrary(libraryId);
    AssetGrant grant =
        AssetGrant.forGroup(libraryId, organizationId, groupId, AssetRole.EDITOR, null, userId);
    when(grantRepository.findByLibraryId(libraryId)).thenReturn(List.of(grant));
    when(membershipResolver.groupIdsForUser(userId)).thenReturn(Set.of(groupId));

    assertThat(accessService.canManage(library, userId, false)).isFalse();
    assertThat(accessService.canRead(library, userId, false)).isTrue();
    assertThat(accessService.effectiveRole(library, userId, false)).isEqualTo(AssetRole.EDITOR);
  }

  @Test
  void organizationWideVisibilityGrantsViewerToAnyOrganizationMemberWithoutAGrant() {
    UUID libraryId = UUID.randomUUID();
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            organizationId,
            "Bibliothek",
            null,
            UUID.randomUUID(),
            LibraryVisibility.ORGANIZATION,
            false);
    setId(library, libraryId);
    when(grantRepository.findByLibraryId(libraryId)).thenReturn(List.of());

    assertThat(accessService.canRead(library, userId, false)).isTrue();
    assertThat(accessService.canManage(library, userId, false)).isFalse();
  }

  @Test
  void readableLibraryIdsUnionsDirectGroupAndOrganizationWideLibrariesWithoutASystemAdminBypass() {
    UUID directLibrary = UUID.randomUUID();
    UUID groupLibrary = UUID.randomUUID();
    UUID orgLibrary = UUID.randomUUID();
    UUID groupId = UUID.randomUUID();
    when(membershipResolver.groupIdsForUser(userId)).thenReturn(Set.of(groupId));
    when(grantRepository.findReadableLibraryIdsByDirectGrant(eq(userId), eq(organizationId), any()))
        .thenReturn(Set.of(directLibrary));
    when(grantRepository.findReadableLibraryIdsByGroupGrant(
            eq(Set.of(groupId)), eq(organizationId), any()))
        .thenReturn(Set.of(groupLibrary));
    KnowledgeLibrary orgWide =
        KnowledgeLibrary.ownedByUser(
            organizationId,
            "Organisationsweit",
            null,
            UUID.randomUUID(),
            LibraryVisibility.ORGANIZATION,
            false);
    setId(orgWide, orgLibrary);
    when(libraryRepository.findByOrganizationIdAndVisibility(
            organizationId, LibraryVisibility.ORGANIZATION))
        .thenReturn(List.of(orgWide));

    Set<UUID> readable = accessService.readableLibraryIds(userId, organizationId);

    assertThat(readable).containsExactlyInAnyOrder(directLibrary, groupLibrary, orgLibrary);
  }

  @Test
  void readableLibraryIdsIsEmptyForAUserWithNoGrantsNoGroupsAndNoOrganizationWideLibrary() {
    when(grantRepository.findReadableLibraryIdsByDirectGrant(eq(userId), eq(organizationId), any()))
        .thenReturn(Set.of());
    when(libraryRepository.findByOrganizationIdAndVisibility(
            organizationId, LibraryVisibility.ORGANIZATION))
        .thenReturn(List.of());

    assertThat(accessService.readableLibraryIds(userId, organizationId)).isEmpty();
    verify(grantRepository, never()).findReadableLibraryIdsByGroupGrant(any(), any(), any());
  }

  @Test
  void invalidateLibraryEvictsTheCachedGrantListSoTheNextCheckReadsAgain() {
    UUID libraryId = UUID.randomUUID();
    KnowledgeLibrary library = privateUserOwnedLibrary(libraryId);
    when(grantRepository.findByLibraryId(libraryId)).thenReturn(List.of());
    assertThat(accessService.canRead(library, userId, false)).isFalse();

    AssetGrant grant =
        AssetGrant.forUser(libraryId, organizationId, userId, AssetRole.VIEWER, null, userId);
    when(grantRepository.findByLibraryId(libraryId)).thenReturn(List.of(grant));
    accessService.invalidateLibrary(libraryId);

    assertThat(accessService.canRead(library, userId, false)).isTrue();
    verify(grantRepository, org.mockito.Mockito.times(2)).findByLibraryId(libraryId);
  }

  @Test
  void effectiveRolesForReadableLibrariesResolvesEachRoleFromASingleBatchQuery() {
    // #425 review, finding 1 and nit 4: this method exists so listLibraries never combines the
    // uncached readableLibraryIds membership with the separately-cached, potentially stale
    // effectiveRole - both must read grants from the exact same, single query.
    UUID directGrantLibraryId = UUID.randomUUID();
    UUID groupGrantLibraryId = UUID.randomUUID();
    UUID groupId = UUID.randomUUID();
    KnowledgeLibrary directGrantLibrary = privateUserOwnedLibrary(directGrantLibraryId);
    KnowledgeLibrary groupGrantLibrary = privateUserOwnedLibrary(groupGrantLibraryId);
    when(membershipResolver.groupIdsForUser(userId)).thenReturn(Set.of(groupId));
    when(grantRepository.findByLibraryIdIn(Set.of(directGrantLibraryId, groupGrantLibraryId)))
        .thenReturn(
            List.of(
                userGrant(directGrantLibraryId, userId, AssetRole.EDITOR),
                AssetGrant.forGroup(
                    groupGrantLibraryId,
                    organizationId,
                    groupId,
                    AssetRole.MANAGER,
                    null,
                    userId)));

    var roles =
        accessService.effectiveRolesForReadableLibraries(
            List.of(directGrantLibrary, groupGrantLibrary), userId);

    assertThat(roles)
        .containsEntry(directGrantLibraryId, AssetRole.EDITOR)
        .containsEntry(groupGrantLibraryId, AssetRole.MANAGER);
    verify(grantRepository, never()).findByLibraryId(any());
  }

  @Test
  void effectiveRolesForReadableLibrariesFloorsAtViewerWhenNoGrantResolves() {
    // The exact scenario finding 1 describes: a library that is a member of readableLibraryIds
    // (by construction, at least VIEWER) but whose grant this batch query does not resolve to a
    // role - it must never surface as null against a required response field.
    UUID libraryId = UUID.randomUUID();
    KnowledgeLibrary library = privateUserOwnedLibrary(libraryId);
    when(grantRepository.findByLibraryIdIn(Set.of(libraryId))).thenReturn(List.of());

    var roles = accessService.effectiveRolesForReadableLibraries(List.of(library), userId);

    assertThat(roles).containsEntry(libraryId, AssetRole.VIEWER);
  }

  @Test
  void effectiveRolesForReadableLibrariesNeverBypassesToOwnerForASystemAdmin() {
    // Unlike effectiveRole, this method takes no systemAdmin parameter at all - listLibraries
    // deliberately never bypasses (#425 review, orchestrator decision on nit 3).
    UUID libraryId = UUID.randomUUID();
    KnowledgeLibrary library = privateUserOwnedLibrary(libraryId);
    when(grantRepository.findByLibraryIdIn(Set.of(libraryId))).thenReturn(List.of());

    var roles = accessService.effectiveRolesForReadableLibraries(List.of(library), userId);

    assertThat(roles.get(libraryId)).isNotEqualTo(AssetRole.OWNER);
  }
}
