package io.opaa.indexing.metadata;

import java.util.UUID;

/**
 * The two model-backed extraction switches of a library with the chat role they would use
 * (metadata-schema.md, "Die modellgestützte Extraktion im Betrieb").
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
