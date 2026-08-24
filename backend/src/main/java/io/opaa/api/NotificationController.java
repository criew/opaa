package io.opaa.api;

import io.opaa.api.dto.NotificationResponse;
import io.opaa.auth.CurrentUser;
import io.opaa.notification.Notification;
import io.opaa.notification.NotificationService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

  private static final int DEFAULT_LIMIT = 50;
  private static final int MAX_LIMIT = 100;

  private final NotificationService notificationService;

  public NotificationController(NotificationService notificationService) {
    this.notificationService = notificationService;
  }

  @GetMapping
  public List<NotificationResponse> listNotifications(
      @RequestParam(required = false) Integer limit, CurrentUser caller) {
    int resolvedLimit = limit == null ? DEFAULT_LIMIT : limit;
    if (resolvedLimit < 1 || resolvedLimit > MAX_LIMIT) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "limit muss zwischen 1 und " + MAX_LIMIT + " liegen");
    }
    return notificationService.listForRecipient(caller.id(), resolvedLimit).stream()
        .map(this::toResponse)
        .toList();
  }

  @PostMapping("/{notificationId}/read")
  public ResponseEntity<Void> markRead(@PathVariable UUID notificationId, CurrentUser caller) {
    notificationService.markRead(notificationId, caller.id());
    return ResponseEntity.noContent().build();
  }

  private NotificationResponse toResponse(Notification notification) {
    return new NotificationResponse(
            notification.getId(),
            notification.getType(),
            notification.getTitle(),
            notification.getCreatedAt())
        .objectType(notification.getObjectType())
        .objectId(notification.getObjectId())
        .body(notification.getBody())
        .readAt(notification.getReadAt());
  }
}
