package io.opaa.auth;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.api.types.NotificationType;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.common.ConflictException;
import io.opaa.notification.NotificationService;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The separation of system administration and revision for the AUDITOR role: what an open request
 * is, who learns of it and of every grant. A system administrator never grants the role alone -
 * {@link UserService#updateRole} turns the grant into a request here while the organization has an
 * auditor, and only the account {@link AuditorRoleProperties} names may become the first one. Every
 * write runs in the caller's transaction.
 */
@Component
public class AuditorRoles {

  public static final String AUDITOR_ACCOUNT = "AUDITOR_ACCOUNT";
  public static final String AUDITOR_CONFIRMATION_UNAVAILABLE = "AUDITOR_CONFIRMATION_UNAVAILABLE";

  private final AuditorRoleRequestRepository requests;
  private final UserRepository users;
  private final LocalCredentialsRepository localCredentials;
  private final AuditorRoleProperties properties;
  private final AuditEventRecorder audit;
  private final NotificationService notifications;
  private final Clock clock;

  public AuditorRoles(
      AuditorRoleRequestRepository requests,
      UserRepository users,
      LocalCredentialsRepository localCredentials,
      AuditorRoleProperties properties,
      AuditEventRecorder audit,
      NotificationService notifications,
      Clock clock) {
    this.requests = requests;
    this.users = users;
    this.localCredentials = localCredentials;
    this.properties = properties;
    this.audit = audit;
    this.notifications = notifications;
    this.clock = clock;
  }

  /** Whether the organization has an account holding the AUDITOR role, locked or not. */
  public boolean organizationHasAuditor(UUID organizationId) {
    return !auditorsOf(organizationId).isEmpty();
  }

  public boolean namesFirstAuditor(User user) {
    return properties.namesFirstAuditor(user.getEmail());
  }

  /**
   * Whether the account holds the AUDITOR role or has an open request for it - the accounts whose
   * credentials the system administration must never hold.
   */
  @Transactional(readOnly = true)
  public boolean isAuditorOrRequested(User user) {
    return user.getSystemRole() == SystemRole.AUDITOR
        || requests.findByUserId(user.getId()).isPresent();
  }

  /**
   * The credentials of an auditor account - or of one with an open AUDITOR request - stay with its
   * person: the administration neither sets nor resets its password, changes its address or hands
   * it over, since each would give it a way into the account. {@code what} names the refused part.
   */
  @Transactional(readOnly = true)
  public void requireCredentialsWithPerson(User user, String what) {
    if (isAuditorOrRequested(user)) {
      throw new ConflictException(
          what
              + " eines Kontos der Revision ändert nur die Person selbst; die Systemverwaltung"
              + " kann es nicht setzen, zurücksetzen oder übergeben.",
          AUDITOR_ACCOUNT);
    }
  }

  @Transactional(readOnly = true)
  public Optional<AuditorRoleRequest> openRequestOf(UUID userId) {
    return requests.findByUserId(userId);
  }

  /** The ids among {@code userIds} with an open request. */
  @Transactional(readOnly = true)
  public Set<UUID> withOpenRequest(Collection<UUID> userIds) {
    if (userIds.isEmpty()) {
      return Set.of();
    }
    return requests.findByUserIdIn(userIds).stream()
        .map(AuditorRoleRequest::getUserId)
        .collect(Collectors.toSet());
  }

  /** An open request with the two accounts it names, as the deciding auditor sees it. */
  public record OpenRequest(
      AuditorRoleRequest request, User user, User requestedBy, boolean localAccount) {}

  /** The open requests of the organization, oldest first. */
  @Transactional(readOnly = true)
  public List<OpenRequest> openRequests(UUID organizationId) {
    return requests.findByOrganizationIdOrderByRequestedAtAsc(organizationId).stream()
        .map(
            open ->
                new OpenRequest(
                    open,
                    users.findById(open.getUserId()).orElseThrow(),
                    users.findById(open.getRequestedByUserId()).orElseThrow(),
                    localCredentials.existsById(open.getUserId())))
        .toList();
  }

  /**
   * Opens the request for {@code user}, audited under the requesting administrator, and tells every
   * auditor; an already open request stays as it is.
   */
  @Transactional
  public void request(User user, CurrentUser actor) {
    if (requests.findByUserId(user.getId()).isPresent()) {
      return;
    }
    requests.saveAndFlush(
        new AuditorRoleRequest(
            user.getOrganizationId(), user.getId(), actor.id(), clock.instant()));
    record(
        user,
        actor.id(),
        AuditEventType.AUDITOR_ROLE_REQUESTED,
        user.getSystemRole(),
        SystemRole.AUDITOR);
    notifyAuditors(
        user,
        NotificationType.AUDITOR_ROLE_REQUESTED,
        "Rolle Revision beantragt",
        "Die Systemverwaltung beantragt die Rolle Revision für "
            + nameOf(user)
            + ". Sie wird erst wirksam, wenn eine Person der Revision unter Revision →"
            + " Rollenanträge bestätigt.");
  }

  /**
   * Ends the open request of {@code user} without the role, audited under {@code actorUserId} - an
   * auditor declining, or the administration setting another role; nothing when none is open.
   */
  @Transactional
  public void decline(User user, UUID actorUserId) {
    requests
        .findByUserId(user.getId())
        .ifPresent(
            open -> {
              requests.delete(open);
              requests.flush();
              record(
                  user,
                  actorUserId,
                  AuditEventType.AUDITOR_ROLE_REQUEST_DECLINED,
                  SystemRole.AUDITOR,
                  user.getSystemRole());
            });
  }

  /**
   * A granted role closes the request that asked for it and is told to every other auditor of the
   * organization, whichever way it came.
   */
  @EventListener
  public void onGranted(AuditorRoleGrantedEvent event) {
    User user = event.user();
    requests.findByUserId(user.getId()).ifPresent(requests::delete);
    notifyAuditors(
        user,
        NotificationType.AUDITOR_ROLE_GRANTED,
        "Neue Person in der Revision",
        nameOf(user) + " hat die Rolle Revision erhalten.");
  }

  private void notifyAuditors(User user, NotificationType type, String title, String body) {
    for (User auditor : auditorsOf(user.getOrganizationId())) {
      if (auditor.getId().equals(user.getId())) {
        continue;
      }
      notifications.notify(
          user.getOrganizationId(),
          auditor.getId(),
          type,
          AuditObjectType.USER_ACCOUNT,
          user.getId(),
          title,
          body);
    }
  }

  private List<User> auditorsOf(UUID organizationId) {
    return users.findByOrganizationIdAndSystemRole(organizationId, SystemRole.AUDITOR);
  }

  private static String nameOf(User user) {
    if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
      return user.getDisplayName();
    }
    return user.getEmail() == null ? "ein Konto" : user.getEmail();
  }

  private void record(
      User user, UUID actorUserId, AuditEventType type, SystemRole before, SystemRole after) {
    UUID pseudonym = audit.pseudonymFor(user.getId(), user.getOrganizationId());
    audit.recordUserActionOnSubject(
        AuditEvent.builder()
            .organizationId(user.getOrganizationId())
            .actor(actorUserId)
            .type(type)
            .object(AuditObjectType.USER_ACCOUNT, pseudonym, null)
            .subject(AuditSubjectKind.USER, user.getId())
            .before(Map.of("role", before.name()))
            .after(Map.of("role", after.name()))
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }
}
