package io.opaa.notification;

import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.NotificationType;
import io.opaa.common.NotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates and serves minimal in-app notifications (#203) - see {@link Notification}'s Javadoc for
 * why this stays deliberately narrow.
 */
@Service
@Transactional(readOnly = true)
public class NotificationService {

  private final NotificationRepository notificationRepository;

  public NotificationService(NotificationRepository notificationRepository) {
    this.notificationRepository = notificationRepository;
  }

  @Transactional
  public void notify(
      UUID organizationId,
      UUID recipientUserId,
      NotificationType type,
      AuditObjectType objectType,
      UUID objectId,
      String title,
      String body) {
    notificationRepository.save(
        new Notification(organizationId, recipientUserId, type, objectType, objectId, title, body));
  }

  /** The recipient's newest notifications, newest first, capped at {@code limit} (#706 review). */
  public List<Notification> listForRecipient(UUID currentUserId, int limit) {
    return notificationRepository.findByRecipientUserIdOrderByCreatedAtDesc(
        currentUserId, PageRequest.of(0, limit));
  }

  @Transactional
  public void markRead(UUID notificationId, UUID currentUserId) {
    Notification notification =
        notificationRepository
            .findById(notificationId)
            .orElseThrow(() -> new NotFoundException("Benachrichtigung nicht gefunden"));
    if (!notification.getRecipientUserId().equals(currentUserId)) {
      throw new NotFoundException("Benachrichtigung nicht gefunden");
    }
    notification.markRead();
    notificationRepository.save(notification);
  }
}
