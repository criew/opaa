package io.opaa.api.dto;

import java.time.Instant;

public record ErrorResponse(String error, int status, Instant timestamp) {}
