package io.opaa.api.dto;

import io.opaa.workspace.WorkspaceRole;
import io.opaa.workspace.WorkspaceType;
import java.time.Instant;
import java.util.UUID;

public record WorkspaceListResponse(
    UUID id,
    String name,
    String description,
    WorkspaceType type,
    int memberCount,
    WorkspaceRole userRole,
    Instant createdAt,
    Instant updatedAt) {}
