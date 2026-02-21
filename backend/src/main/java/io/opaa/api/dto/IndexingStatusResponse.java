package io.opaa.api.dto;

import java.time.Instant;

public record IndexingStatusResponse(
    IndexingStatus status, int documentCount, String message, Instant timestamp) {}
