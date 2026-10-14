package io.opaa.query;

import io.opaa.chat.Chat;
import io.opaa.chat.ChatNotePoint;
import io.opaa.chat.UsedPrompt;
import io.opaa.metadata.MetadataFilter;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Takes over a whole turn instead of the ordinary retrieval/answer path of {@link QueryService},
 * with the chat, space guards, readable libraries and search scope the caller has already resolved.
 * A bean exists only while {@code opaa.spike.tool-loop.enabled} is set.
 */
public interface ToolLoopQueryHandler {

  /**
   * Empty for any question the handler declines - the caller then runs its own, unmodified path.
   * Otherwise runs and persists the turn and returns the finished {@link QueryResult}.
   *
   * @param startTime {@code System.currentTimeMillis()} at the start of the caller's turn, for
   *     {@link QueryOutcome#durationMs()} - the same reference point the ordinary path uses.
   * @param usedPrompt the checked prompt the question was built from, or {@code null}; persisted
   *     with the question like on the ordinary path.
   */
  Optional<QueryResult> handle(
      String rawQuestion,
      Optional<Chat> chat,
      UUID effectiveChatId,
      String conversationKey,
      List<ChatNotePoint> notePoints,
      Set<UUID> searchScope,
      MetadataFilter metadataFilter,
      long startTime,
      UsedPrompt usedPrompt);
}
