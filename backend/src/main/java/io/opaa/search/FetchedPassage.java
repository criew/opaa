package io.opaa.search;

import io.opaa.chat.ChatSourceMetadataEntry;
import java.util.List;
import java.util.UUID;

/**
 * The text behind one hit (#1720): the passage with its adjoining passages, or - only on explicit
 * request - the whole extracted text of the document.
 *
 * @param whole whether {@code text} is the whole document rather than the passage with context.
 * @param truncated whether {@code text} was cut at {@code characterLimit}. Possible on both paths:
 *     with the delivered values a passage and its neighbours stay far below the cap, but {@code
 *     contextPassages} and the chunk size are configurable upwards and the cap downwards.
 * @param downloadable whether the original behind this passage may be offered for download - the
 *     library is in the effective view of the request. Decided in {@link PassageFetchService},
 *     never in the response mapper, so the download path cannot be handed out past the scope.
 */
public record FetchedPassage(
    String hitId,
    UUID documentId,
    String fileName,
    String title,
    UUID libraryId,
    String libraryName,
    Integer chunkIndex,
    String location,
    List<String> headingPath,
    String text,
    boolean whole,
    boolean truncated,
    int characterLimit,
    List<ChatSourceMetadataEntry> metadata,
    boolean downloadable) {}
