package io.opaa.workspace;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class WorkspaceService {

  private final WorkspaceRepository workspaceRepository;

  public WorkspaceService(WorkspaceRepository workspaceRepository) {
    this.workspaceRepository = workspaceRepository;
  }

  public List<Workspace> findByUser(UUID userId) {
    return workspaceRepository.findDistinctByMembershipsUserId(userId);
  }

  public Optional<Workspace> findById(UUID workspaceId) {
    return workspaceRepository.findById(workspaceId);
  }
}
