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
import io.opaa.audit.AuditEventRecorder;
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
  private PermissionHistoryService permissionHistoryService;
  private AuditEventRecorder auditEventRecorder;
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
    permissionHistoryService = mock(PermissionHistoryService.class);
    auditEventRecorder = mock(AuditEventRecorder.class);
    grantService =
        new AssetGrantService(
            grantRepository,
            libraryRepository,
            userRepository,
            groupRepository,
            accessService,
            permissionHistoryService,
            auditEventRecorder);

    // KnowledgeLibrary.ownedByUser always assigns its own random id (like every other factory
    // method on that entity) - libraryId is read back from the constructed instance rather than
    // generated independently, so every stub keyed on "this library's id" below actually matches
    // what AssetGrantService reads via library.getId().
    library =
        KnowledgeLibrary.ownedByUser(
            organizationId, "Bibliothek", null, managerId, LibraryVisibility.PRIVATE, false);
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
    // #392 code review, finding 2: the subject must resolve (existence + organization boundary)
    // before the escalation guard even runs - see AssetGrantService#upsertGrant. A subject id with
    // no stubbed userRepository.findById would now fail with 404 before the guard is ever reached,
    // testing the wrong thing; a real, resolvable subject in the same organization keeps this test
    // exercising the escalation guard specifically.
    UUID subjectId = UUID.randomUUID();
    User subjectUser = new User("subject", "issuer", "subject@example.com", "Subject");
    subjectUser.setOrganizationId(organizationId);
    when(userRepository.findById(subjectId)).thenReturn(Optional.of(subjectUser));
    AssetGrantRequest request =
        new AssetGrantRequest(PermissionSubjectType.USER, subjectId, AssetRole.OWNER);

    assertThatThrownBy(() -> grantService.upsertGrant(libraryId, request, managerId, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex -> {
              ResponseStatusException responseStatusException = (ResponseStatusException) ex;
              assertThat(responseStatusException.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
              // #448: the message names the role in German ("Eigentümer"), not the raw enum
              // constant ("OWNER") - the escalation guard's whole point is a message an end user
              // (not just a developer reading logs) can act on.
              assertThat(responseStatusException.getReason())
                  .isEqualTo(
                      "Die eigene Rolle reicht nicht aus, um die Rolle Eigentümer zu vergeben");
            });
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
            ex -> {
              ResponseStatusException responseStatusException = (ResponseStatusException) ex;
              assertThat(responseStatusException.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
              // #448: correct German umlaut ("aufgelöst"), not the umlaut-free "aufgeloest".
              assertThat(responseStatusException.getReason())
                  .isEqualTo(
                      "Die Gruppe ist aufgelöst und kann keine neuen Berechtigungen mehr"
                          + " erhalten");
            });
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
    when(grantRepository.countOtherActiveOwnerGrants(
            eq(libraryId), eq(onlyOwnerGrant.getId()), any()))
        .thenReturn(0L);

    AssetGrantRequest request =
        new AssetGrantRequest(PermissionSubjectType.USER, subjectId, AssetRole.VIEWER);

    assertThatThrownBy(() -> grantService.upsertGrant(libraryId, request, managerId, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex -> {
              ResponseStatusException responseStatusException = (ResponseStatusException) ex;
              assertThat(responseStatusException.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
              // #448 code review: "Eigentümer", nicht die rohe Enum-Konstante "OWNER".
              assertThat(responseStatusException.getReason())
                  .isEqualTo(
                      "Die letzte Eigentümer-Berechtigung einer Bibliothek kann nicht"
                          + " herabgestuft werden");
            });
    verify(grantRepository, never()).save(any());
  }

  @Test
  void revokeGrantRejectsRemovingTheLastActiveOwnerGrant() {
    when(accessService.canManage(any(), eq(managerId), anyBoolean())).thenReturn(true);
    when(accessService.effectiveRole(any(), eq(managerId), anyBoolean()))
        .thenReturn(AssetRole.OWNER);
    UUID grantId = UUID.randomUUID();
    AssetGrant onlyOwnerGrant =
        AssetGrant.forUser(libraryId, organizationId, managerId, AssetRole.OWNER, null, managerId);
    when(grantRepository.findById(grantId)).thenReturn(Optional.of(onlyOwnerGrant));
    when(grantRepository.countOtherActiveOwnerGrants(
            eq(libraryId), eq(onlyOwnerGrant.getId()), any()))
        .thenReturn(0L);

    assertThatThrownBy(() -> grantService.revokeGrant(libraryId, grantId, managerId, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex -> {
              ResponseStatusException responseStatusException = (ResponseStatusException) ex;
              assertThat(responseStatusException.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
              // #448 code review: "Eigentümer", nicht die rohe Enum-Konstante "OWNER".
              assertThat(responseStatusException.getReason())
                  .isEqualTo(
                      "Die letzte Eigentümer-Berechtigung einer Bibliothek kann nicht entfernt"
                          + " werden");
            });
    verify(grantRepository, never()).delete(any());
  }

  @Test
  void revokeGrantAllowsRemovingAnOwnerGrantWhenAnotherActiveOwnerGrantRemains() {
    when(accessService.canManage(any(), eq(managerId), anyBoolean())).thenReturn(true);
    when(accessService.effectiveRole(any(), eq(managerId), anyBoolean()))
        .thenReturn(AssetRole.OWNER);
    UUID grantId = UUID.randomUUID();
    AssetGrant grantToRemove =
        AssetGrant.forUser(libraryId, organizationId, managerId, AssetRole.OWNER, null, managerId);
    when(grantRepository.findById(grantId)).thenReturn(Optional.of(grantToRemove));
    // Keyed on grantToRemove's own id (not the lookup id grantId) - AssetGrantService passes
    // grant.getId() to the guard, and AssetGrant.forUser mints its own random id independent of
    // grantId.
    when(grantRepository.countOtherActiveOwnerGrants(
            eq(libraryId), eq(grantToRemove.getId()), any()))
        .thenReturn(1L);

    grantService.revokeGrant(libraryId, grantId, managerId, false);

    verify(grantRepository).delete(grantToRemove);
  }

  @Test
  void revokeGrantRejectsRemovingAGrantWithARoleHigherThanTheCallersOwnRoleEvenIfNotTheLastOwner() {
    // #202 code review round 2 (Befund 1): the escalation guard on the *existing* grant's role
    // must fire independently of the last-active-OWNER guard, before it - even when another active
    // OWNER grant remains (so the last-owner guard alone would allow the removal), a caller who
    // only holds MANAGER may still never remove a grant that already carries OWNER. Previously
    // revokeGrant never called effectiveRole at all, so this scenario passed with a 200 instead of
    // this 403.
    when(accessService.canManage(any(), eq(managerId), anyBoolean())).thenReturn(true);
    when(accessService.effectiveRole(any(), eq(managerId), anyBoolean()))
        .thenReturn(AssetRole.MANAGER);
    UUID grantId = UUID.randomUUID();
    AssetGrant ownerGrantToRemove =
        AssetGrant.forUser(
            libraryId, organizationId, UUID.randomUUID(), AssetRole.OWNER, null, managerId);
    when(grantRepository.findById(grantId)).thenReturn(Optional.of(ownerGrantToRemove));
    // Deliberately stubbed even though the test asserts it is never called: proves the rejection
    // below is not an accidental side effect of an unstubbed count defaulting to 0 and the
    // last-active-OWNER guard firing for the wrong reason - a second active OWNER grant genuinely
    // exists, so that guard alone would allow the removal.
    when(grantRepository.countOtherActiveOwnerGrants(
            eq(libraryId), eq(ownerGrantToRemove.getId()), any()))
        .thenReturn(1L);

    assertThatThrownBy(() -> grantService.revokeGrant(libraryId, grantId, managerId, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex -> {
              ResponseStatusException responseStatusException = (ResponseStatusException) ex;
              assertThat(responseStatusException.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
              // #448: the existing grant's role is named as "Eigentümer", not the raw "OWNER".
              assertThat(responseStatusException.getReason())
                  .isEqualTo(
                      "Die eigene Rolle reicht nicht aus, um eine bestehende"
                          + " Eigentümer-Berechtigung zu entfernen");
            });
    verify(grantRepository, never()).delete(any());
    // The role-escalation guard must short-circuit before the last-active-OWNER count is even
    // read - a MANAGER is refused for the more fundamental reason regardless of how many other
    // OWNER grants exist.
    verify(grantRepository, never()).countOtherActiveOwnerGrants(any(), any(), any());
  }

  @Test
  void upsertGrantRejectsDowngradingAnExistingGrantWithARoleHigherThanTheCallersOwnRole() {
    // The update-path counterpart of the revoke test above: a MANAGER downgrading an existing
    // OWNER grant to something lower is exactly as much an escalation as revoking it outright, and
    // must be rejected the same way, independent of the last-active-OWNER guard.
    when(accessService.canManage(any(), eq(managerId), anyBoolean())).thenReturn(true);
    when(accessService.effectiveRole(any(), eq(managerId), anyBoolean()))
        .thenReturn(AssetRole.MANAGER);
    UUID subjectId = UUID.randomUUID();
    User subjectUser = new User("subject", "issuer", "subject@example.com", "Subject");
    subjectUser.setOrganizationId(organizationId);
    when(userRepository.findById(subjectId)).thenReturn(Optional.of(subjectUser));
    AssetGrant existingOwnerGrant =
        AssetGrant.forUser(libraryId, organizationId, subjectId, AssetRole.OWNER, null, managerId);
    when(grantRepository.findByLibraryIdAndSubjectTypeAndSubjectUserId(
            libraryId, PermissionSubjectType.USER, subjectId))
        .thenReturn(Optional.of(existingOwnerGrant));

    AssetGrantRequest request =
        new AssetGrantRequest(PermissionSubjectType.USER, subjectId, AssetRole.VIEWER);

    assertThatThrownBy(() -> grantService.upsertGrant(libraryId, request, managerId, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex -> {
              ResponseStatusException responseStatusException = (ResponseStatusException) ex;
              assertThat(responseStatusException.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
              // #448: "ändern" with the correct umlaut, not the umlaut-free "aendern".
              assertThat(responseStatusException.getReason())
                  .isEqualTo(
                      "Die eigene Rolle reicht nicht aus, um eine bestehende"
                          + " Eigentümer-Berechtigung zu ändern");
            });
    verify(grantRepository, never()).save(any());
  }

  @Test
  void upsertGrantRejectsSettingTheLastActiveOwnerGrantsExpiryIntoThePast() {
    // #202 code review round 2 (nit 1): "newRole == OWNER is always allowed" was too coarse - an
    // OWNER renewing their own sole grant with role = OWNER but expiresAt in the past expires it
    // immediately, leaving the library without any active OWNER. The count the guard protects must
    // be taken after the intended change, including the new expiresAt, not just the new role.
    when(accessService.canManage(any(), eq(managerId), anyBoolean())).thenReturn(true);
    when(accessService.effectiveRole(any(), eq(managerId), anyBoolean()))
        .thenReturn(AssetRole.OWNER);
    AssetGrant onlyOwnerGrant =
        AssetGrant.forUser(libraryId, organizationId, managerId, AssetRole.OWNER, null, managerId);
    when(grantRepository.findByLibraryIdAndSubjectTypeAndSubjectUserId(
            libraryId, PermissionSubjectType.USER, managerId))
        .thenReturn(Optional.of(onlyOwnerGrant));
    when(grantRepository.countOtherActiveOwnerGrants(
            eq(libraryId), eq(onlyOwnerGrant.getId()), any()))
        .thenReturn(0L);

    AssetGrantRequest request =
        new AssetGrantRequest(PermissionSubjectType.USER, managerId, AssetRole.OWNER)
            .expiresAt(Instant.now().minusSeconds(60));

    assertThatThrownBy(() -> grantService.upsertGrant(libraryId, request, managerId, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
    verify(grantRepository, never()).save(any());
  }

  @Test
  void upsertGrantAllowsRenewingTheLastActiveOwnerGrantWithAFutureOrNoExpiry() {
    // The positive counterpart of the test above: role = OWNER with either no expiry or a
    // still-future one genuinely keeps the grant active, so the guard must not fire.
    when(accessService.canManage(any(), eq(managerId), anyBoolean())).thenReturn(true);
    when(accessService.effectiveRole(any(), eq(managerId), anyBoolean()))
        .thenReturn(AssetRole.OWNER);
    AssetGrant onlyOwnerGrant =
        AssetGrant.forUser(libraryId, organizationId, managerId, AssetRole.OWNER, null, managerId);
    when(grantRepository.findByLibraryIdAndSubjectTypeAndSubjectUserId(
            libraryId, PermissionSubjectType.USER, managerId))
        .thenReturn(Optional.of(onlyOwnerGrant));
    when(grantRepository.save(any(AssetGrant.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    AssetGrantRequest request =
        new AssetGrantRequest(PermissionSubjectType.USER, managerId, AssetRole.OWNER);
    var response = grantService.upsertGrant(libraryId, request, managerId, false);

    assertThat(response.getRole()).isEqualTo(AssetRole.OWNER);
    verify(grantRepository, never()).countOtherActiveOwnerGrants(any(), any(), any());
  }

  // #423 code review, finding 1: subjectDisplayName/grantedByDisplayName must be resolved by the
  // backend so a caller without SYSTEM_ADMIN - the threshold GET /v1/admin/users and
  // /v1/admin/groups both require - still sees names instead of raw UUIDs. Every test in this class
  // already calls listGrants/upsertGrant/revokeGrant with systemAdmin = false, so these tests below
  // deliberately do the same: resolution must not depend on that flag.
  @Test
  void listGrantsResolvesUserSubjectAndGranterDisplayNames() {
    when(accessService.canManage(any(), eq(managerId), anyBoolean())).thenReturn(true);
    User subjectUser = new User("subject", "issuer", "subject@example.com", "Subjekt Person");
    subjectUser.setOrganizationId(organizationId);
    User granter = new User("granter", "issuer", "granter@example.com", "Erteilende Person");
    granter.setOrganizationId(organizationId);
    // subjectUser.getId()/granter.getId() (not an independently generated UUID) - findAllById's
    // stub below matches entities by their own id, same as the real JpaRepository would.
    AssetGrant grant =
        AssetGrant.forUser(
            libraryId,
            organizationId,
            subjectUser.getId(),
            AssetRole.VIEWER,
            null,
            granter.getId());
    when(grantRepository.findByLibraryId(libraryId)).thenReturn(List.of(grant));
    when(userRepository.findAllById(any())).thenReturn(List.of(subjectUser, granter));

    var responses = grantService.listGrants(libraryId, managerId, false);

    assertThat(responses).hasSize(1);
    assertThat(responses.get(0).getSubjectDisplayName()).isEqualTo("Subjekt Person");
    assertThat(responses.get(0).getGrantedByDisplayName()).isEqualTo("Erteilende Person");
  }

  @Test
  void listGrantsResolvesGroupSubjectDisplayName() {
    when(accessService.canManage(any(), eq(managerId), anyBoolean())).thenReturn(true);
    Group group = new Group(organizationId, GroupKind.AD_HOC, "Referat 50", null, null, null);
    AssetGrant grant =
        AssetGrant.forGroup(
            libraryId, organizationId, group.getId(), AssetRole.VIEWER, null, managerId);
    when(grantRepository.findByLibraryId(libraryId)).thenReturn(List.of(grant));
    when(groupRepository.findAllById(any())).thenReturn(List.of(group));

    var responses = grantService.listGrants(libraryId, managerId, false);

    assertThat(responses).hasSize(1);
    assertThat(responses.get(0).getSubjectDisplayName()).isEqualTo("Referat 50");
  }

  @Test
  void listGrantsFallsBackToEmailWhenTheSubjectsDisplayNameIsUnset() {
    // #446 code review round 2: a User whose token never carried a name/preferred_username claim
    // has displayName == null (UserService#findOrCreateUser only overwrites it when the incoming
    // claim is non-null). Falling back to the raw subject id there would reopen the same
    // "MANAGER sees a UUID" gap this whole resolution mechanism exists to close - email is
    // required and always present, so it is the fallback instead.
    when(accessService.canManage(any(), eq(managerId), anyBoolean())).thenReturn(true);
    User subjectUser = new User("subject", "issuer", "subject@example.com", null);
    subjectUser.setOrganizationId(organizationId);
    AssetGrant grant =
        AssetGrant.forUser(
            libraryId, organizationId, subjectUser.getId(), AssetRole.VIEWER, null, null);
    when(grantRepository.findByLibraryId(libraryId)).thenReturn(List.of(grant));
    when(userRepository.findAllById(any())).thenReturn(List.of(subjectUser));

    var responses = grantService.listGrants(libraryId, managerId, false);

    assertThat(responses).hasSize(1);
    assertThat(responses.get(0).getSubjectDisplayName()).isEqualTo("subject@example.com");
  }

  @Test
  void listGrantsLeavesDisplayNameNullWhenTheSubjectNoLongerExists() {
    when(accessService.canManage(any(), eq(managerId), anyBoolean())).thenReturn(true);
    AssetGrant grant =
        AssetGrant.forUser(
            libraryId, organizationId, UUID.randomUUID(), AssetRole.VIEWER, null, null);
    when(grantRepository.findByLibraryId(libraryId)).thenReturn(List.of(grant));
    // Deliberately not stubbing userRepository.findAllById - a Mockito mock's default answer for
    // an unstubbed List-returning method is an empty list, exercising the "subject deleted" branch.

    var responses = grantService.listGrants(libraryId, managerId, false);

    assertThat(responses).hasSize(1);
    assertThat(responses.get(0).getSubjectDisplayName()).isNull();
    assertThat(responses.get(0).getGrantedByDisplayName()).isNull();
  }
}
