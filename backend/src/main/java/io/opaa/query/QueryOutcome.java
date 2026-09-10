package io.opaa.query;

import java.util.List;

/**
 * The per-turn metadata of one answer - model, token count, duration and the libraries searched.
 * {@code QueryController} maps it to the generated {@code QueryMetadata}.
 *
 * @param searchedLibraries the libraries the retrieval actually searched, {@code null} when the
 *     turn did not resolve a search scope at all.
 */
public record QueryOutcome(
    String model,
    int tokenCount,
    long durationMs,
    boolean answeredWithoutKnowledge,
    boolean noKnowledgeAvailableInSpace,
    List<SearchedLibraryRef> searchedLibraries) {}
