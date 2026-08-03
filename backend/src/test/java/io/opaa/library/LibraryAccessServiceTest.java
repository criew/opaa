package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
            false,
            false);
    setId(library, libraryId);
    return library;
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
  void systemLibraryIsReadableOnlyBySystemAdminsRegardlessOfGrants() {
    KnowledgeLibrary systemLibrary = mock(KnowledgeLibrary.class);
    when(systemLibrary.isSystemLibrary()).thenReturn(true);

    assertThat(accessService.canRead(systemLibrary, userId, false)).isFalse();
    assertThat(accessService.canRead(systemLibrary, userId, true)).isTrue();
    // Never even asks for the grant list - the fail-closed check short-circuits first.
    verify(grantRepository, never()).findByLibraryId(any());
  }

  @Test
  void systemAdminBypassesGrantsOnAnOrdinaryLibrary() {
    UUID libraryId = UUID.randomUUID();
    KnowledgeLibrary library = privateUserOwnedLibrary(libraryId);
    when(grantRepository.findByLibraryId(libraryId)).thenReturn(List.of());

    assertThat(accessService.canManage(library, userId, true)).isTrue();
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
  void aDirectManagerGrantAllowsManagingButNotDeleting() {
    // #202 code review round 3 (Blocker 1): MANAGER is enough to rename, change visibility or
    // manage grants, but never enough to delete the library or (once it exists) transfer its
    // ownership - AssetRole reserves that for OWNER alone.
    UUID libraryId = UUID.randomUUID();
    KnowledgeLibrary library = privateUserOwnedLibrary(libraryId);
    AssetGrant grant =
        AssetGrant.forUser(libraryId, organizationId, userId, AssetRole.MANAGER, null, userId);
    when(grantRepository.findByLibraryId(libraryId)).thenReturn(List.of(grant));

    assertThat(accessService.canManage(library, userId, false)).isTrue();
    assertThat(accessService.canDelete(library, userId, false)).isFalse();
  }

  @Test
  void aDirectOwnerGrantAllowsDeleting() {
    UUID libraryId = UUID.randomUUID();
    KnowledgeLibrary library = privateUserOwnedLibrary(libraryId);
    AssetGrant grant =
        AssetGrant.forUser(libraryId, organizationId, userId, AssetRole.OWNER, null, userId);
    when(grantRepository.findByLibraryId(libraryId)).thenReturn(List.of(grant));

    assertThat(accessService.canDelete(library, userId, false)).isTrue();
  }

  @Test
  void aUserOnlyGrantDoesNotAllowReadingConfiguration() {
    // #202's central distinction: USER can use the asset but not see its configuration.
    UUID libraryId = UUID.randomUUID();
    KnowledgeLibrary library = privateUserOwnedLibrary(libraryId);
    AssetGrant grant =
        AssetGrant.forUser(libraryId, organizationId, userId, AssetRole.USER, null, userId);
    when(grantRepository.findByLibraryId(libraryId)).thenReturn(List.of(grant));

    assertThat(accessService.canRead(library, userId, false)).isFalse();
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
            false,
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
            false,
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
}
