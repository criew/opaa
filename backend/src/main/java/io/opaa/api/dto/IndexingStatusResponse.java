package io.opaa.api.dto;

import java.time.Instant;

public record IndexingStatusResponse(
    IndexingStatus status,
    int documentCount,
    int totalDocuments,
    int documentsSkipped,
    String message,
    Instant timestamp) {}
