package io.opaa.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

@DataJpaTest
@TestPropertySource(
    properties = {"spring.liquibase.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"})
class WorkspaceRepositoryTest {

  @Autowired private WorkspaceRepository workspaceRepository;
  @Autowired private WorkspaceMembershipRepository workspaceMembershipRepository;

  @Test
  void findDistinctByMembershipsUserIdReturnsUserWorkspaces() {
    UUID userA = UUID.randomUUID();
    UUID userB = UUID.randomUUID();

    Workspace eng = new Workspace("Engineering", "Engineering docs", WorkspaceType.SHARED, userA);
    eng.addMembership(new WorkspaceMembership(userA, WorkspaceRole.OWNER));
    eng.addMembership(new WorkspaceMembership(userB, WorkspaceRole.EDITOR));

    Workspace hr = new Workspace("HR", "HR docs", WorkspaceType.SHARED, userB);
    hr.addMembership(new WorkspaceMembership(userB, WorkspaceRole.OWNER));

    workspaceRepository.saveAll(List.of(eng, hr));

    List<Workspace> userAWorkspaces = workspaceRepository.findDistinctByMembershipsUserId(userA);
    List<Workspace> userBWorkspaces = workspaceRepository.findDistinctByMembershipsUserId(userB);

    assertThat(userAWorkspaces).extracting(Workspace::getName).containsExactly("Engineering");
    assertThat(userBWorkspaces)
        .extracting(Workspace::getName)
        .containsExactlyInAnyOrder("Engineering", "HR");
  }

  @Test
  void findByWorkspaceIdReturnsMembersForWorkspace() {
    UUID owner = UUID.randomUUID();
    UUID editor = UUID.randomUUID();
    Workspace workspace =
        new Workspace("Phoenix", "Project workspace", WorkspaceType.SHARED, owner);
    workspace.addMembership(new WorkspaceMembership(owner, WorkspaceRole.OWNER));
    workspace.addMembership(new WorkspaceMembership(editor, WorkspaceRole.EDITOR));

    Workspace savedWorkspace = workspaceRepository.save(workspace);

    List<WorkspaceMembership> members =
        workspaceMembershipRepository.findByWorkspaceId(savedWorkspace.getId());

    assertThat(members).hasSize(2);
    assertThat(members)
        .extracting(WorkspaceMembership::getRole)
        .containsExactlyInAnyOrder(WorkspaceRole.OWNER, WorkspaceRole.EDITOR);
  }

  @Test
  void deletingWorkspaceRemovesMemberships() {
    UUID owner = UUID.randomUUID();
    Workspace workspace =
        new Workspace("Company", "Company-wide workspace", WorkspaceType.SHARED, owner);
    workspace.addMembership(new WorkspaceMembership(owner, WorkspaceRole.OWNER));

    Workspace savedWorkspace = workspaceRepository.save(workspace);
    UUID workspaceId = savedWorkspace.getId();

    assertThat(workspaceMembershipRepository.findByWorkspaceId(workspaceId)).hasSize(1);

    workspaceRepository.delete(savedWorkspace);

    assertThat(workspaceRepository.findById(workspaceId)).isEmpty();
    assertThat(workspaceMembershipRepository.findByWorkspaceId(workspaceId)).isEmpty();
  }
}
