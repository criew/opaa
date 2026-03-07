package io.opaa.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.dto.WorkspaceListResponse;
import io.opaa.api.dto.WorkspaceMemberRequest;
import io.opaa.api.dto.WorkspaceRequest;
import io.opaa.api.dto.WorkspaceResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@DataJpaTest
@Import(WorkspaceService.class)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(
    properties = {"spring.liquibase.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"})
class WorkspaceServiceIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg18"));

  @DynamicPropertySource
  static void configureDataSource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkspaceRepository workspaceRepository;
  @Autowired private WorkspaceMembershipRepository membershipRepository;

  @BeforeEach
  void cleanUp() {
    membershipRepository.deleteAll();
    workspaceRepository.deleteAll();
  }

  @Test
  void systemAdminCanCreateSharedWorkspace() {
    UUID adminUserId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    UUID editorId = UUID.randomUUID();
    WorkspaceRequest request =
        new WorkspaceRequest(
            "Engineering",
            "Engineering docs",
            ownerId,
            List.of(new WorkspaceMemberRequest(editorId, WorkspaceRole.EDITOR)));

    WorkspaceResponse created = workspaceService.createWorkspace(request, adminUserId, true);

    assertThat(created.type()).isEqualTo(WorkspaceType.SHARED);
    assertThat(created.name()).isEqualTo("Engineering");
    assertThat(created.memberCount()).isEqualTo(2);
    assertThat(created.roleCounts().get(WorkspaceRole.OWNER)).isEqualTo(1);
    assertThat(created.roleCounts().get(WorkspaceRole.EDITOR)).isEqualTo(1);
  }

  @Test
  void nonSystemAdminCannotCreateWorkspace() {
    WorkspaceRequest request =
        new WorkspaceRequest("Engineering", "Engineering docs", UUID.randomUUID(), List.of());

    assertThatThrownBy(() -> workspaceService.createWorkspace(request, UUID.randomUUID(), false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void listReturnsOnlyMembershipWorkspaces() {
    UUID userA = UUID.randomUUID();
    UUID userB = UUID.randomUUID();
    Workspace eng = new Workspace("Engineering", "Engineering docs", WorkspaceType.SHARED, userA);
    eng.addMembership(new WorkspaceMembership(userA, WorkspaceRole.OWNER));
    eng.addMembership(new WorkspaceMembership(userB, WorkspaceRole.EDITOR));
    Workspace hr = new Workspace("HR", "HR docs", WorkspaceType.SHARED, userB);
    hr.addMembership(new WorkspaceMembership(userB, WorkspaceRole.OWNER));
    workspaceRepository.saveAll(List.of(eng, hr));

    List<WorkspaceListResponse> userAWorkspaces = workspaceService.listWorkspaces(userA);

    assertThat(userAWorkspaces).hasSize(1);
    assertThat(userAWorkspaces.getFirst().name()).isEqualTo("Engineering");
    assertThat(userAWorkspaces.getFirst().userRole()).isEqualTo(WorkspaceRole.OWNER);
  }

  @Test
  void detailsIncludeMemberCountAndCurrentUsersRole() {
    UUID owner = UUID.randomUUID();
    UUID viewer = UUID.randomUUID();
    Workspace workspace = new Workspace("Phoenix", "Project docs", WorkspaceType.SHARED, owner);
    workspace.addMembership(new WorkspaceMembership(owner, WorkspaceRole.OWNER));
    workspace.addMembership(new WorkspaceMembership(viewer, WorkspaceRole.VIEWER));
    Workspace saved = workspaceRepository.save(workspace);

    WorkspaceResponse response = workspaceService.getWorkspace(saved.getId(), viewer, false);

    assertThat(response.memberCount()).isEqualTo(2);
    assertThat(response.userRole()).isEqualTo(WorkspaceRole.VIEWER);
    assertThat(response.roleCounts().get(WorkspaceRole.OWNER)).isEqualTo(1);
    assertThat(response.roleCounts().get(WorkspaceRole.VIEWER)).isEqualTo(1);
  }

  @Test
  void deletingWorkspaceRemovesMemberships() {
    UUID owner = UUID.randomUUID();
    UUID editor = UUID.randomUUID();
    Workspace workspace = new Workspace("Company", "Company docs", WorkspaceType.SHARED, owner);
    workspace.addMembership(new WorkspaceMembership(owner, WorkspaceRole.OWNER));
    workspace.addMembership(new WorkspaceMembership(editor, WorkspaceRole.EDITOR));
    Workspace saved = workspaceRepository.save(workspace);

    workspaceService.deleteWorkspace(saved.getId(), owner, false);

    assertThat(workspaceRepository.findById(saved.getId())).isEmpty();
    assertThat(membershipRepository.findByWorkspaceId(saved.getId())).isEmpty();
  }

  @Test
  void deletingPersonalWorkspaceReturnsBadRequest() {
    UUID owner = UUID.randomUUID();
    Workspace personal =
        new Workspace("My Documents", "Private docs", WorkspaceType.PERSONAL, owner);
    personal.addMembership(new WorkspaceMembership(owner, WorkspaceRole.OWNER));
    Workspace saved = workspaceRepository.save(personal);

    assertThatThrownBy(() -> workspaceService.deleteWorkspace(saved.getId(), owner, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void duplicateWorkspaceNameReturnsConflict() {
    UUID admin = UUID.randomUUID();
    UUID ownerA = UUID.randomUUID();
    UUID ownerB = UUID.randomUUID();
    workspaceService.createWorkspace(
        new WorkspaceRequest("Engineering", "A", ownerA, List.of()), admin, true);

    assertThatThrownBy(
            () ->
                workspaceService.createWorkspace(
                    new WorkspaceRequest("engineering", "B", ownerB, List.of()), admin, true))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  void adminCanAddViewerAndEditor() {
    UUID owner = UUID.randomUUID();
    UUID admin = UUID.randomUUID();
    UUID viewer = UUID.randomUUID();
    UUID editor = UUID.randomUUID();
    Workspace workspace = new Workspace("Team", "Team docs", WorkspaceType.SHARED, owner);
    workspace.addMembership(new WorkspaceMembership(owner, WorkspaceRole.OWNER));
    workspace.addMembership(new WorkspaceMembership(admin, WorkspaceRole.ADMIN));
    Workspace saved = workspaceRepository.save(workspace);

    workspaceService.addMember(saved.getId(), viewer, WorkspaceRole.VIEWER, admin);
    workspaceService.addMember(saved.getId(), editor, WorkspaceRole.EDITOR, admin);

    Workspace reloaded = workspaceRepository.findByIdWithMemberships(saved.getId()).orElseThrow();
    assertThat(reloaded.getMemberships()).hasSize(4);
    assertThat(reloaded.getMemberships())
        .filteredOn(member -> member.getUserId().equals(viewer))
        .extracting(WorkspaceMembership::getRole)
        .containsExactly(WorkspaceRole.VIEWER);
    assertThat(reloaded.getMemberships())
        .filteredOn(member -> member.getUserId().equals(editor))
        .extracting(WorkspaceMembership::getRole)
        .containsExactly(WorkspaceRole.EDITOR);
  }

  @Test
  void adminCannotAddAdminMember() {
    UUID owner = UUID.randomUUID();
    UUID admin = UUID.randomUUID();
    Workspace workspace = new Workspace("Team", "Team docs", WorkspaceType.SHARED, owner);
    workspace.addMembership(new WorkspaceMembership(owner, WorkspaceRole.OWNER));
    workspace.addMembership(new WorkspaceMembership(admin, WorkspaceRole.ADMIN));
    Workspace saved = workspaceRepository.save(workspace);

    assertThatThrownBy(
            () ->
                workspaceService.addMember(
                    saved.getId(), UUID.randomUUID(), WorkspaceRole.ADMIN, admin))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void cannotAddMembersToPersonalWorkspace() {
    UUID owner = UUID.randomUUID();
    Workspace personal = new Workspace("My Documents", "Private", WorkspaceType.PERSONAL, owner);
    personal.addMembership(new WorkspaceMembership(owner, WorkspaceRole.OWNER));
    Workspace saved = workspaceRepository.save(personal);

    assertThatThrownBy(
            () ->
                workspaceService.addMember(
                    saved.getId(), UUID.randomUUID(), WorkspaceRole.VIEWER, owner))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void adminCannotRemoveOwner() {
    UUID owner = UUID.randomUUID();
    UUID admin = UUID.randomUUID();
    Workspace workspace = new Workspace("Team", "Team docs", WorkspaceType.SHARED, owner);
    workspace.addMembership(new WorkspaceMembership(owner, WorkspaceRole.OWNER));
    workspace.addMembership(new WorkspaceMembership(admin, WorkspaceRole.ADMIN));
    Workspace saved = workspaceRepository.save(workspace);

    assertThatThrownBy(() -> workspaceService.removeMember(saved.getId(), owner, admin))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void ownerCanTransferOwnership() {
    UUID owner = UUID.randomUUID();
    UUID admin = UUID.randomUUID();
    Workspace workspace = new Workspace("Team", "Team docs", WorkspaceType.SHARED, owner);
    workspace.addMembership(new WorkspaceMembership(owner, WorkspaceRole.OWNER));
    workspace.addMembership(new WorkspaceMembership(admin, WorkspaceRole.ADMIN));
    Workspace saved = workspaceRepository.save(workspace);

    workspaceService.transferOwnership(saved.getId(), admin, owner);

    Workspace reloaded = workspaceRepository.findByIdWithMemberships(saved.getId()).orElseThrow();
    assertThat(reloaded.getMemberships())
        .filteredOn(member -> member.getUserId().equals(owner))
        .extracting(WorkspaceMembership::getRole)
        .containsExactly(WorkspaceRole.ADMIN);
    assertThat(reloaded.getMemberships())
        .filteredOn(member -> member.getUserId().equals(admin))
        .extracting(WorkspaceMembership::getRole)
        .containsExactly(WorkspaceRole.OWNER);
  }

  @Test
  void roleChangesAreImmediatelyEffective() {
    UUID owner = UUID.randomUUID();
    UUID admin = UUID.randomUUID();
    UUID editor = UUID.randomUUID();
    Workspace workspace = new Workspace("Team", "Team docs", WorkspaceType.SHARED, owner);
    workspace.addMembership(new WorkspaceMembership(owner, WorkspaceRole.OWNER));
    workspace.addMembership(new WorkspaceMembership(admin, WorkspaceRole.ADMIN));
    workspace.addMembership(new WorkspaceMembership(editor, WorkspaceRole.EDITOR));
    Workspace saved = workspaceRepository.save(workspace);

    workspaceService.updateMemberRole(saved.getId(), editor, WorkspaceRole.VIEWER, admin);
    WorkspaceResponse details = workspaceService.getWorkspace(saved.getId(), editor, false);

    assertThat(details.userRole()).isEqualTo(WorkspaceRole.VIEWER);
  }

  @Test
  void viewerCannotChangeRoles() {
    UUID owner = UUID.randomUUID();
    UUID viewer = UUID.randomUUID();
    UUID target = UUID.randomUUID();
    Workspace workspace = new Workspace("Team", "Team docs", WorkspaceType.SHARED, owner);
    workspace.addMembership(new WorkspaceMembership(owner, WorkspaceRole.OWNER));
    workspace.addMembership(new WorkspaceMembership(viewer, WorkspaceRole.VIEWER));
    workspace.addMembership(new WorkspaceMembership(target, WorkspaceRole.EDITOR));
    Workspace saved = workspaceRepository.save(workspace);

    assertThatThrownBy(
            () ->
                workspaceService.updateMemberRole(
                    saved.getId(), target, WorkspaceRole.VIEWER, viewer))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void adminCannotPromoteMemberToOwner() {
    UUID owner = UUID.randomUUID();
    UUID admin = UUID.randomUUID();
    UUID editor = UUID.randomUUID();
    Workspace workspace = new Workspace("Team", "Team docs", WorkspaceType.SHARED, owner);
    workspace.addMembership(new WorkspaceMembership(owner, WorkspaceRole.OWNER));
    workspace.addMembership(new WorkspaceMembership(admin, WorkspaceRole.ADMIN));
    workspace.addMembership(new WorkspaceMembership(editor, WorkspaceRole.EDITOR));
    Workspace saved = workspaceRepository.save(workspace);

    assertThatThrownBy(
            () ->
                workspaceService.updateMemberRole(
                    saved.getId(), editor, WorkspaceRole.OWNER, admin))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));
  }
}
