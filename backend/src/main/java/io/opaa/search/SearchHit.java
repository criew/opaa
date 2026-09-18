package io.opaa.search;

import io.opaa.chat.ChatSourceMetadataEntry;
import java.util.List;
import java.util.UUID;

/**
 * One retrieved passage as the reading path returns it (#1720) - per passage, not per document: the
 * passage is what a foreign tool quotes and what {@link PassageFetchService} fetches.
 *
 * @param hitId the passage's own chunk id, stable and unguessable. A countable id would turn the
 *     fetch path into a listing over foreign holdings even though every single fetch checks rights;
 *     the permission filter stays the barrier, unguessability is the second layer.
 * @param relevanceScore the reciprocal of the hit's 1-based position, the same rank-derived value a
 *     Beleg of {@code POST /api/v1/query} carries - a raw score is not comparable between the
 *     lexical and the vector path.
 * @param downloadable whether the original behind this hit may be offered for download - the
 *     library is in the effective view of the request. Decided in {@link SearchService}, never in
 *     the response mapper, so the download path cannot be handed out past the scope.
 */
public record SearchHit(
    String hitId,
    String title,
    String excerpt,
    UUID libraryId,
    String libraryName,
    UUID documentId,
    String fileName,
    Integer chunkIndex,
    String location,
    List<ChatSourceMetadataEntry> metadata,
    double relevanceScore,
    boolean downloadable) {}
