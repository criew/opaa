package io.opaa.api.dto;

import java.time.Instant;

public record IndexingStatusResponse(
    IndexingStatus status, int documentCount, int chunkCount, String message, Instant timestamp) {}
