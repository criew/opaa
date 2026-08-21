package io.opaa.notification;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

  List<Notification> findByRecipientUserIdOrderByCreatedAtDesc(UUID recipientUserId);
}
