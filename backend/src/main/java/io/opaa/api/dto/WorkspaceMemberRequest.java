package io.opaa.api.dto;

import io.opaa.workspace.WorkspaceRole;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record WorkspaceMemberRequest(@NotNull UUID userId, @NotNull WorkspaceRole role) {}
