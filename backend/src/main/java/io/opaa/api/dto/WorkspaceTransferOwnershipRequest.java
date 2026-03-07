package io.opaa.api.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record WorkspaceTransferOwnershipRequest(@NotNull UUID userId) {}
