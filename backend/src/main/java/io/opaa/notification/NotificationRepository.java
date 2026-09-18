package io.opaa.notification;

import io.opaa.api.types.NotificationType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

  List<Notification> findByRecipientUserIdOrderByCreatedAtDesc(UUID recipientUserId);

  /**
   * The recipient's newest notifications, capped at {@code pageable}'s page size (#706 review:
   * {@code GET /v1/notifications} needed a limit, this table has no retention policy of its own
   * yet) - {@link org.springframework.data.domain.PageRequest#of(int, int)} with page 0 is the
   * usual caller.
   */
  List<Notification> findByRecipientUserIdOrderByCreatedAtDesc(
      UUID recipientUserId, Pageable pageable);

  /**
   * Removes every notification of one type created before {@code cutoff}, read or not - the shape a
   * type with its own retention period needs (#1720). The table has no blanket retention policy;
   * only a type that declares one is cleaned up.
   */
  int deleteByTypeAndCreatedAtBefore(NotificationType type, Instant cutoff);
}
