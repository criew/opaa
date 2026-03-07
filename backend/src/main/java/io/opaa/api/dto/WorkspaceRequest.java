package io.opaa.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record WorkspaceRequest(
    @NotBlank @Size(max = 255) String name,
    @Size(max = 2000) String description,
    UUID ownerId,
    List<@Valid WorkspaceMemberRequest> initialMembers) {}
