package io.opaa.api;

import io.opaa.api.dto.NotificationResponse;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import io.opaa.notification.Notification;
import io.opaa.notification.NotificationService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

  private static final String UNKNOWN_ISSUER = "unknown";

  private final NotificationService notificationService;
  private final UserService userService;

  public NotificationController(NotificationService notificationService, UserService userService) {
    this.notificationService = notificationService;
    this.userService = userService;
  }

  @GetMapping
  public List<NotificationResponse> listNotifications(@AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    return notificationService.listForRecipient(currentUser.getId()).stream()
        .map(this::toResponse)
        .toList();
  }

  @PostMapping("/{notificationId}/read")
  public ResponseEntity<Void> markRead(
      @PathVariable UUID notificationId, @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    notificationService.markRead(notificationId, currentUser.getId());
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

  private User currentUser(Jwt jwt) {
    String issuer = jwt.getClaimAsString("iss");
    if (issuer == null || issuer.isBlank()) {
      issuer = UNKNOWN_ISSUER;
    }

    return userService
        .findBySubjectAndIssuer(jwt.getSubject(), issuer)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Benutzer nicht gefunden"));
  }
}
