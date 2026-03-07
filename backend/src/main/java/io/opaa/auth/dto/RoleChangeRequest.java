package io.opaa.auth.dto;

import io.opaa.auth.SystemRole;
import jakarta.validation.constraints.NotNull;

public record RoleChangeRequest(@NotNull SystemRole role) {}
