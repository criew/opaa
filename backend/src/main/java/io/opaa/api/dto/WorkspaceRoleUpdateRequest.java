package io.opaa.api.dto;

import io.opaa.workspace.WorkspaceRole;
import jakarta.validation.constraints.NotNull;

public record WorkspaceRoleUpdateRequest(@NotNull WorkspaceRole role) {}
