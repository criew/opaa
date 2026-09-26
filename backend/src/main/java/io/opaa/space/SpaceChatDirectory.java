package io.opaa.space;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * What this package needs to know about the chats living in a space, answered by the chat package.
 * Both reads run in the caller's transaction.
 */
public interface SpaceChatDirectory {

  /** Whether any chat, by any author, still lives in the space. */
  boolean existsInSpace(UUID spaceId);

  /**
   * The number of chats {@code authorId} wrote per space, for the given spaces; a space without
   * such a chat is absent from the map.
   */
  Map<UUID, Long> countByAuthorPerSpace(Collection<UUID> spaceIds, UUID authorId);
}
