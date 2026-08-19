package io.opaa.chat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatRepository extends JpaRepository<Chat, UUID> {

  /**
   * The one lookup every chat-scoped operation (get/update/delete, and the persisted-chat path of
   * {@code QueryService#query}) goes through - a chat visible to no one but its author (#525), so
   * "exists but belongs to someone else" and "does not exist" collapse into the same empty result
   * on purpose.
   */
  Optional<Chat> findByIdAndAuthorId(UUID id, UUID authorId);

  List<Chat> findBySpaceIdAndAuthorIdOrderByUpdatedAtDesc(UUID spaceId, UUID authorId);
}
