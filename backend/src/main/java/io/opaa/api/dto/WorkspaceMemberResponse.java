package io.opaa.api.dto;

import io.opaa.workspace.WorkspaceRole;
import java.time.Instant;
import java.util.UUID;

public record WorkspaceMemberResponse(UUID userId, WorkspaceRole role, Instant createdAt) {}
