package io.opaa.search;

import io.opaa.chat.ChatSourceMetadataEntry;
import java.util.List;
import java.util.UUID;

/**
 * The text behind one hit (#1720): the passage with its adjoining passages, or - only on explicit
 * request - the whole extracted text of the document.
 *
 * @param whole whether {@code text} is the whole document rather than the passage with context.
 * @param truncated whether {@code text} was cut at {@code characterLimit}. Only ever true for
 *     {@code whole}: a passage with its neighbours is bounded by the chunk size.
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
    List<ChatSourceMetadataEntry> metadata) {}
