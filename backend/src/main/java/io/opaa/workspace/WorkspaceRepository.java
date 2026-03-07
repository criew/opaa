package io.opaa.workspace;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {

  List<Workspace> findDistinctByMembershipsUserId(UUID userId);
}
