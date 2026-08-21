package io.opaa.notification;

import io.opaa.audit.AuditObjectType;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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

  public List<Notification> listForRecipient(UUID currentUserId) {
    return notificationRepository.findByRecipientUserIdOrderByCreatedAtDesc(currentUserId);
  }

  @Transactional
  public void markRead(UUID notificationId, UUID currentUserId) {
    Notification notification =
        notificationRepository
            .findById(notificationId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Benachrichtigung nicht gefunden"));
    if (!notification.getRecipientUserId().equals(currentUserId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Benachrichtigung nicht gefunden");
    }
    notification.markRead();
    notificationRepository.save(notification);
  }
}
