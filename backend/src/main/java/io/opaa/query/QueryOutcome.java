package io.opaa.query;

import io.opaa.chat.SearchedLibraryRef;
import java.util.List;

/**
 * The per-turn metadata of one answer - model, token count, duration and the libraries searched.
 * {@code QueryController} maps it to the generated {@code QueryMetadata}. At most one of the four
 * flags is set: why the turn searched nothing, if it did not.
 *
 * @param noKnowledgeAssignedToSpace the chat's space has no knowledge library associated
 * @param noKnowledgeAvailableInSpace knowledge is associated, but none of it is readable
 * @param noSpaceContext the question names no chat of the caller and therefore has no space
 * @param searchedLibraries the libraries the retrieval actually searched, {@code null} when the
 *     turn did not resolve a search scope at all.
 */
public record QueryOutcome(
    String model,
    int tokenCount,
    long durationMs,
    boolean answeredWithoutKnowledge,
    boolean noKnowledgeAssignedToSpace,
    boolean noKnowledgeAvailableInSpace,
    boolean noSpaceContext,
    List<SearchedLibraryRef> searchedLibraries) {}
