package io.opaa.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthConfigResponse(String mode, String authority, String clientId) {}
