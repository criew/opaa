package io.opaa.auth.dto;

import java.util.UUID;

public record UserInfoResponse(UUID id, String email, String displayName, String systemRole) {}
