package io.opaa.api.dto;

import io.opaa.workspace.WorkspaceRole;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record WorkspaceAddMemberRequest(@NotNull UUID userId, WorkspaceRole role) {}
