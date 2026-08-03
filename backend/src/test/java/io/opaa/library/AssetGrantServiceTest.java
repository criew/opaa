package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.dto.AssetGrantRequest;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.group.Group;
import io.opaa.group.GroupKind;
import io.opaa.group.GroupRepository;
import io.opaa.group.PermissionSubjectType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class AssetGrantServiceTest {

  private AssetGrantRepository grantRepository;
  private KnowledgeLibraryRepository libraryRepository;
  private UserRepository userRepository;
  private GroupRepository groupRepository;
  private LibraryAccessService accessService;
  private AssetGrantService grantService;

  private final UUID organizationId = UUID.randomUUID();
  private final UUID managerId = UUID.randomUUID();
  private UUID libraryId;
  private KnowledgeLibrary library;

  @BeforeEach
  void setUp() {
    grantRepository = mock(AssetGrantRepository.class);
    libraryRepository = mock(KnowledgeLibraryRepository.class);
    userRepository = mock(UserRepository.class);
    groupRepository = mock(GroupRepository.class);
    accessService = mock(LibraryAccessService.class);
    grantService =
        new AssetGrantService(
            grantRepository, libraryRepository, userRepository, groupRepository, accessService);

    // KnowledgeLibrary.ownedByUser always assigns its own random id (like every other factory
    // method on that entity) - libraryId is read back from the constructed instance rather than
    // generated independently, so every stub keyed on "this library's id" below actually matches
    // what AssetGrantService reads via library.getId().
    library =
        KnowledgeLibrary.ownedByUser(
            organizationId, "Bibliothek", null, managerId, LibraryVisibility.PRIVATE, false, false);
    libraryId = library.getId();
    when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));

    User manager = new User("manager", "issuer", "manager@example.com", "Manager");
    manager.setOrganizationId(organizationId);
    when(userRepository.findById(managerId)).thenReturn(Optional.of(manager));
  }

  @Test
  void upsertGrantRejectsACallerWithoutManagerRole() {
    when(accessService.canManage(any(), any(), anyBoolean())).thenReturn(false);
    AssetGrantRequest request =
        new AssetGrantRequest(PermissionSubjectType.USER, UUID.randomUUID(), AssetRole.VIEWER);

    assertThatThrownBy(() -> grantService.upsertGrant(libraryId, request, managerId, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));
    verify(grantRepository, never()).save(any());
  }

  @Test
  void upsertGrantCreatesADirectUserGrantAndInvalidatesTheLibraryCache() {
    when(accessService.canManage(any(), eq(managerId), anyBoolean())).thenReturn(true);
    when(accessService.effectiveRole(any(), eq(managerId), anyBoolean()))
        .thenReturn(AssetRole.OWNER);
    UUID subjectId = UUID.randomUUID();
    User subjectUser = new User("subject", "issuer", "subject@example.com", "Subject");
    subjectUser.setOrganizationId(organizationId);
    when(userRepository.findById(subjectId)).thenReturn(Optional.of(subjectUser));
    when(grantRepository.findByLibraryIdAndSubjectTypeAndSubjectUserId(
            libraryId, PermissionSubjectType.USER, subjectId))
        .thenReturn(Optional.empty());
    when(grantRepository.save(any(AssetGrant.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    AssetGrantRequest request =
        new AssetGrantRequest(PermissionSubjectType.USER, subjectId, AssetRole.VIEWER);
    var response = grantService.upsertGrant(libraryId, request, managerId, false);

    assertThat(response.getSubjectId()).isEqualTo(subjectId);
    assertThat(response.getRole()).isEqualTo(AssetRole.VIEWER);
    // No active transaction synchronization in this unit test, so invalidation runs immediately -
    // see AssetGrantService#invalidateAfterCommit's fallback branch.
    verify(accessService).invalidateLibrary(libraryId);
  }

  @Test
  void upsertGrantRejectsASubjectUserFromAnotherOrganizationAsNotFound() {
    when(accessService.canManage(any(), eq(managerId), anyBoolean())).thenReturn(true);
    when(accessService.effectiveRole(any(), eq(managerId), anyBoolean()))
        .thenReturn(AssetRole.OWNER);
    UUID foreignUserId = UUID.randomUUID();
    User foreignUser = new User("foreign", "issuer", "foreign@example.com", "Foreign");
    foreignUser.setOrganizationId(UUID.randomUUID());
    when(userRepository.findById(foreignUserId)).thenReturn(Optional.of(foreignUser));

    AssetGrantRequest request =
        new AssetGrantRequest(PermissionSubjectType.USER, foreignUserId, AssetRole.VIEWER);

    assertThatThrownBy(() -> grantService.upsertGrant(libraryId, request, managerId, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void upsertGrantRejectsASubjectGroupFromAnotherOrganizationAsNotFound() {
    when(accessService.canManage(any(), eq(managerId), anyBoolean())).thenReturn(true);
    when(accessService.effectiveRole(any(), eq(managerId), anyBoolean()))
        .thenReturn(AssetRole.OWNER);
    UUID foreignGroupId = UUID.randomUUID();
    Group foreignGroup = new Group(UUID.randomUUID(), GroupKind.AD_HOC, "Fremd", null, null, null);
    when(groupRepository.findById(foreignGroupId)).thenReturn(Optional.of(foreignGroup));

    AssetGrantRequest request =
        new AssetGrantRequest(PermissionSubjectType.GROUP, foreignGroupId, AssetRole.VIEWER);

    assertThatThrownBy(() -> grantService.upsertGrant(libraryId, request, managerId, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void upsertGrantUpdatesAnExistingGrantsRoleInsteadOfCreatingADuplicate() {
    when(accessService.canManage(any(), eq(managerId), anyBoolean())).thenReturn(true);
    when(accessService.effectiveRole(any(), eq(managerId), anyBoolean()))
        .thenReturn(AssetRole.OWNER);
    UUID subjectId = UUID.randomUUID();
    User subjectUser = new User("subject", "issuer", "subject@example.com", "Subject");
    subjectUser.setOrganizationId(organizationId);
    when(userRepository.findById(subjectId)).thenReturn(Optional.of(subjectUser));
    AssetGrant existing =
        AssetGrant.forUser(libraryId, organizationId, subjectId, AssetRole.VIEWER, null, managerId);
    when(grantRepository.findByLibraryIdAndSubjectTypeAndSubjectUserId(
            libraryId, PermissionSubjectType.USER, subjectId))
        .thenReturn(Optional.of(existing));
    when(grantRepository.save(any(AssetGrant.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    AssetGrantRequest request =
        new AssetGrantRequest(PermissionSubjectType.USER, subjectId, AssetRole.MANAGER);
    var response = grantService.upsertGrant(libraryId, request, managerId, false);

    assertThat(response.getRole()).isEqualTo(AssetRole.MANAGER);
    verify(grantRepository, never()).save(argThat((AssetGrant g) -> g != existing));
  }

  @Test
  void revokeGrantRemovesTheGrantAndInvalidatesTheLibraryCache() {
    when(accessService.canManage(any(), eq(managerId), anyBoolean())).thenReturn(true);
    when(accessService.effectiveRole(any(), eq(managerId), anyBoolean()))
        .thenReturn(AssetRole.OWNER);
    UUID grantId = UUID.randomUUID();
    AssetGrant grant =
        AssetGrant.forUser(
            libraryId, organizationId, UUID.randomUUID(), AssetRole.VIEWER, null, managerId);
    when(grantRepository.findById(grantId)).thenReturn(Optional.of(grant));

    grantService.revokeGrant(libraryId, grantId, managerId, false);

    verify(grantRepository).delete(grant);
    verify(accessService).invalidateLibrary(libraryId);
  }

  @Test
  void revokeGrantTreatsAGrantFromAnotherLibraryAsNotFound() {
    when(accessService.canManage(any(), eq(managerId), anyBoolean())).thenReturn(true);
    when(accessService.effectiveRole(any(), eq(managerId), anyBoolean()))
        .thenReturn(AssetRole.OWNER);
    UUID grantId = UUID.randomUUID();
    AssetGrant grantOnAnotherLibrary =
        AssetGrant.forUser(
            UUID.randomUUID(),
            organizationId,
            UUID.randomUUID(),
            AssetRole.VIEWER,
            null,
            managerId);
    when(grantRepository.findById(grantId)).thenReturn(Optional.of(grantOnAnotherLibrary));

    assertThatThrownBy(() -> grantService.revokeGrant(libraryId, grantId, managerId, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
    verify(grantRepository, never()).delete(any());
  }

  @Test
  void upsertGrantRejectsGrantingARoleHigherThanTheCallersOwnRole() {
    // #202 code review (blocker 3): being a MANAGER is enough to grant *some* role, not enough to
    // grant OWNER - only an OWNER may hand out OWNER, or a MANAGER could grant itself OWNER and
    // then delete the library or transfer ownership, rights the spec reserves for OWNER alone.
    when(accessService.canManage(any(), eq(managerId), anyBoolean())).thenReturn(true);
    when(accessService.effectiveRole(any(), eq(managerId), anyBoolean()))
        .thenReturn(AssetRole.MANAGER);
    AssetGrantRequest request =
        new AssetGrantRequest(PermissionSubjectType.USER, UUID.randomUUID(), AssetRole.OWNER);

    assertThatThrownBy(() -> grantService.upsertGrant(libraryId, request, managerId, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));
    verify(grantRepository, never()).save(any());
  }

  @Test
  void upsertGrantAllowsGrantingExactlyTheCallersOwnRole() {
    when(accessService.canManage(any(), eq(managerId), anyBoolean())).thenReturn(true);
    when(accessService.effectiveRole(any(), eq(managerId), anyBoolean()))
        .thenReturn(AssetRole.MANAGER);
    UUID subjectId = UUID.randomUUID();
    User subjectUser = new User("subject", "issuer", "subject@example.com", "Subject");
    subjectUser.setOrganizationId(organizationId);
    when(userRepository.findById(subjectId)).thenReturn(Optional.of(subjectUser));
    when(grantRepository.save(any(AssetGrant.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    AssetGrantRequest request =
        new AssetGrantRequest(PermissionSubjectType.USER, subjectId, AssetRole.MANAGER);
    var response = grantService.upsertGrant(libraryId, request, managerId, false);

    assertThat(response.getRole()).isEqualTo(AssetRole.MANAGER);
  }

  @Test
  void upsertGrantRejectsAGrantOnThePersonalLibrary() {
    KnowledgeLibrary personalLibrary =
        KnowledgeLibrary.ownedByUser(
            organizationId,
            "Meine Dokumente",
            null,
            managerId,
            LibraryVisibility.PRIVATE,
            false,
            true);
    UUID personalLibraryId = personalLibrary.getId();
    when(libraryRepository.findById(personalLibraryId)).thenReturn(Optional.of(personalLibrary));
    when(accessService.canManage(any(), eq(managerId), anyBoolean())).thenReturn(true);
    when(accessService.effectiveRole(any(), eq(managerId), anyBoolean()))
        .thenReturn(AssetRole.OWNER);
    AssetGrantRequest request =
        new AssetGrantRequest(PermissionSubjectType.USER, UUID.randomUUID(), AssetRole.VIEWER);

    assertThatThrownBy(() -> grantService.upsertGrant(personalLibraryId, request, managerId, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
    verify(grantRepository, never()).save(any());
  }

  @Test
  void upsertGrantRejectsTargetingADissolvedGroup() {
    when(accessService.canManage(any(), eq(managerId), anyBoolean())).thenReturn(true);
    when(accessService.effectiveRole(any(), eq(managerId), anyBoolean()))
        .thenReturn(AssetRole.OWNER);
    Group dissolvedGroup =
        new Group(organizationId, GroupKind.AD_HOC, "Aufgeloest", null, null, null);
    dissolvedGroup.dissolve(Instant.now());
    when(groupRepository.findById(dissolvedGroup.getId())).thenReturn(Optional.of(dissolvedGroup));

    AssetGrantRequest request =
        new AssetGrantRequest(
            PermissionSubjectType.GROUP, dissolvedGroup.getId(), AssetRole.VIEWER);

    assertThatThrownBy(() -> grantService.upsertGrant(libraryId, request, managerId, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
    verify(grantRepository, never()).save(any());
  }

  @Test
  void upsertGrantRejectsDowngradingTheLastActiveOwnerGrant() {
    // #202 code review (blocker 3, extended to the update path): downgrading the sole active
    // OWNER grant is exactly as dangerous as revoking it outright - both leave nobody able to
    // manage the library.
    when(accessService.canManage(any(), eq(managerId), anyBoolean())).thenReturn(true);
    when(accessService.effectiveRole(any(), eq(managerId), anyBoolean()))
        .thenReturn(AssetRole.OWNER);
    UUID subjectId = UUID.randomUUID();
    User subjectUser = new User("subject", "issuer", "subject@example.com", "Subject");
    subjectUser.setOrganizationId(organizationId);
    when(userRepository.findById(subjectId)).thenReturn(Optional.of(subjectUser));
    AssetGrant onlyOwnerGrant =
        AssetGrant.forUser(libraryId, organizationId, subjectId, AssetRole.OWNER, null, managerId);
    when(grantRepository.findByLibraryIdAndSubjectTypeAndSubjectUserId(
            libraryId, PermissionSubjectType.USER, subjectId))
        .thenReturn(Optional.of(onlyOwnerGrant));
    when(grantRepository.findByLibraryId(libraryId)).thenReturn(List.of(onlyOwnerGrant));

    AssetGrantRequest request =
        new AssetGrantRequest(PermissionSubjectType.USER, subjectId, AssetRole.VIEWER);

    assertThatThrownBy(() -> grantService.upsertGrant(libraryId, request, managerId, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
    verify(grantRepository, never()).save(any());
  }

  @Test
  void revokeGrantRejectsRemovingTheLastActiveOwnerGrant() {
    when(accessService.canManage(any(), eq(managerId), anyBoolean())).thenReturn(true);
    UUID grantId = UUID.randomUUID();
    AssetGrant onlyOwnerGrant =
        AssetGrant.forUser(libraryId, organizationId, managerId, AssetRole.OWNER, null, managerId);
    when(grantRepository.findById(grantId)).thenReturn(Optional.of(onlyOwnerGrant));
    when(grantRepository.findByLibraryId(libraryId)).thenReturn(List.of(onlyOwnerGrant));

    assertThatThrownBy(() -> grantService.revokeGrant(libraryId, grantId, managerId, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
    verify(grantRepository, never()).delete(any());
  }

  @Test
  void revokeGrantAllowsRemovingAnOwnerGrantWhenAnotherActiveOwnerGrantRemains() {
    when(accessService.canManage(any(), eq(managerId), anyBoolean())).thenReturn(true);
    UUID grantId = UUID.randomUUID();
    AssetGrant grantToRemove =
        AssetGrant.forUser(libraryId, organizationId, managerId, AssetRole.OWNER, null, managerId);
    AssetGrant otherOwnerGrant =
        AssetGrant.forUser(
            libraryId, organizationId, UUID.randomUUID(), AssetRole.OWNER, null, managerId);
    when(grantRepository.findById(grantId)).thenReturn(Optional.of(grantToRemove));
    when(grantRepository.findByLibraryId(libraryId))
        .thenReturn(List.of(grantToRemove, otherOwnerGrant));

    grantService.revokeGrant(libraryId, grantId, managerId, false);

    verify(grantRepository).delete(grantToRemove);
  }
}
