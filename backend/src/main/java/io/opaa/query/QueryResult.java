package io.opaa.query;

import io.opaa.chat.ChatSource;
import java.util.List;
import java.util.UUID;

/**
 * {@link QueryService#query}'s return type: answer, sources, metadata and the chat it belongs to.
 * {@code QueryController} maps it to the generated response via {@code QueryResponseMapper}.
 *
 * @param chatTitle the chat's title after this turn, {@code null} for a turn outside a chat.
 */
public record QueryResult(
    String answer,
    List<ChatSource> sources,
    QueryOutcome metadata,
    UUID chatId,
    String chatTitle) {}
