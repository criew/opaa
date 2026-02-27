package io.opaa.api.dto;

import java.time.Instant;

public record SourceReference(
    String fileName, double relevanceScore, int matchCount, Instant indexedAt, boolean cited) {}
