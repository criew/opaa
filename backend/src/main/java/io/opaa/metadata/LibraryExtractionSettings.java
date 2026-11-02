package io.opaa.metadata;

import java.util.UUID;

/**
 * The two model-backed extraction switches of a library with the chat role they would use
 * (ADR-0024).
 *
 * @param chatRole {@code null} when no chat model is active at all - the extraction then has
 *     nothing to call, which a client shows instead of a Datenschutzhinweis
 */
public record LibraryExtractionSettings(
    UUID libraryId,
    boolean modelExtractionEnabled,
    boolean keywordsEnabled,
    double confidenceThreshold,
    ChatRoleSummary chatRole) {}
