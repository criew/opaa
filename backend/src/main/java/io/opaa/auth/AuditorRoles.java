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
import io.opaa.mail.MailConfigurationChangedEvent;
import io.opaa.notification.NotificationService;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The separation of system administration and revision for the AUDITOR role: what an open request
 * is, who learns of it and of every grant, withdrawal and change of the mail channels. Neither a
 * system administrator nor a provider's roles claim grants the role alone - {@link
 * UserService#updateRole} and {@link TokenRoleSynchronizer} turn the grant into a request here
 * while the organization has an auditor, and only the local account {@link AuditorRoleProperties}
 * names may become the first one. Every write runs in the caller's transaction.
 */
@Component
public class AuditorRoles {

  public static final String AUDITOR_ACCOUNT = "AUDITOR_ACCOUNT";
  public static final String AUDITOR_CONFIRMATION_UNAVAILABLE = "AUDITOR_CONFIRMATION_UNAVAILABLE";

  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMANY);

  private final AuditorRoleRequestRepository requests;
  private final UserRepository users;
  private final LocalCredentialsRepository localCredentials;
  private final OidcProviderRepository providers;
  private final AuditorRoleProperties properties;
  private final AuditEventRecorder audit;
  private final NotificationService notifications;
  private final Clock clock;

  public AuditorRoles(
      AuditorRoleRequestRepository requests,
      UserRepository users,
      LocalCredentialsRepository localCredentials,
      OidcProviderRepository providers,
      AuditorRoleProperties properties,
      AuditEventRecorder audit,
      NotificationService notifications,
      Clock clock) {
    this.requests = requests;
    this.users = users;
    this.localCredentials = localCredentials;
    this.providers = providers;
    this.properties = properties;
    this.audit = audit;
    this.notifications = notifications;
    this.clock = clock;
  }

  /** Whether the organization has an account holding the AUDITOR role, locked or not. */
  public boolean organizationHasAuditor(UUID organizationId) {
    return !auditorsOf(organizationId).isEmpty();
  }

  /**
   * Whether {@code user} is the local account the operations named as first auditor. An account of
   * an identity provider never is: its address is whatever the provider - possibly run by the same
   * administration - asserts.
   */
  public boolean namesFirstAuditor(User user) {
    return properties.namesFirstAuditor(user.getEmail())
        && localCredentials.existsById(user.getId());
  }

  /**
   * Whether the account holds the AUDITOR role or has an open request for it - the accounts whose
   * credentials the system administration must never hold.
   */
  @Transactional(readOnly = true)
  public boolean isAuditorOrRequested(User user) {
    return user.getSystemRole() == SystemRole.AUDITOR
        || requests.findOpenByUserId(user.getId()).isPresent();
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
    return requests.findOpenByUserId(userId);
  }

  /** The ids among {@code userIds} with an open request. */
  @Transactional(readOnly = true)
  public Set<UUID> withOpenRequest(Collection<UUID> userIds) {
    if (userIds.isEmpty()) {
      return Set.of();
    }
    return new HashSet<>(requests.findOpenUserIdsIn(userIds));
  }

  /**
   * An open request as the deciding auditor sees it: the account, who asked - an administrator's
   * name or the provider's - and when the administration last changed the address of a local
   * account.
   */
  public record OpenRequest(
      AuditorRoleRequest request,
      User user,
      String requestedBy,
      boolean localAccount,
      Instant emailChangedByAdministrationAt) {}

  /** The open requests of the organization, oldest first. */
  @Transactional(readOnly = true)
  public List<OpenRequest> openRequests(UUID organizationId) {
    return requests.findOpenByOrganizationId(organizationId).stream()
        .map(
            open -> {
              Optional<LocalCredentials> local = localCredentials.findById(open.getUserId());
              return new OpenRequest(
                  open,
                  users.findById(open.getUserId()).orElseThrow(),
                  requesterOf(open),
                  local.isPresent(),
                  local.map(LocalCredentials::getEmailChangedByAdminAt).orElse(null));
            })
        .toList();
  }

  /**
   * Opens the request of the administration for {@code user}, audited under the requesting
   * administrator, and tells every auditor; an existing request stays as it is.
   */
  @Transactional
  public void request(User user, CurrentUser actor) {
    if (!open(user, actor.id(), null)) {
      return;
    }
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
            + accountOf(user)
            + "."
            + addressNote(user)
            + " Sie wird erst wirksam, wenn eine Person der Revision unter Revision →"
            + " Rollenanträge bestätigt.");
  }

  /**
   * Opens the request {@code provider}'s roles claim makes for {@code user}, audited under the
   * system actor {@value TokenRoleSynchronizer#IDENTITY_PROVIDER_ACTOR}, and tells every auditor.
   * An existing request - open, or declined while the claim kept naming the role - stays as it is;
   * one indexed read then, since this runs on every sign-in while the request waits. Answers false
   * when the organization has no auditor to decide - the claim then asks for nothing.
   */
  @Transactional
  public boolean requestFromProvider(User user, OidcProvider provider) {
    if (requests.existsByUserId(user.getId())) {
      return true;
    }
    if (!organizationHasAuditor(user.getOrganizationId())) {
      return false;
    }
    if (!open(user, null, provider.getId())) {
      return true;
    }
    recordSystem(
        user,
        AuditEventType.AUDITOR_ROLE_REQUESTED,
        user.getSystemRole(),
        SystemRole.AUDITOR,
        provider);
    notifyAuditors(
        user,
        NotificationType.AUDITOR_ROLE_REQUESTED,
        "Rolle Revision beantragt",
        "Der Identitätsanbieter „"
            + provider.getDisplayName()
            + "“ meldet die Rolle Revision für "
            + accountOf(user)
            + ". Sie wird erst wirksam, wenn eine Person der Revision unter Revision →"
            + " Rollenanträge bestätigt.");
    return true;
  }

  /**
   * The provider's claim no longer names the role: its request ends, open or declined, so a later
   * claim asks anew. One indexed read when there is none - this runs on every sign-in.
   */
  @Transactional
  public void endProviderRequest(User user, OidcProvider provider) {
    if (!requests.existsByUserId(user.getId())) {
      return;
    }
    boolean wasOpen = requests.findOpenByUserId(user.getId()).isPresent();
    if (requests.deleteAllOfUser(user.getId()) == 1 && wasOpen) {
      recordSystem(
          user,
          AuditEventType.AUDITOR_ROLE_REQUEST_DECLINED,
          SystemRole.AUDITOR,
          user.getSystemRole(),
          provider);
    }
  }

  /**
   * Ends the open request of {@code user} without the role, audited under {@code actorUserId} - the
   * administration setting another role; nothing when none is open. Of two concurrent calls only
   * the one that removed the row audits.
   */
  @Transactional
  public void decline(User user, UUID actorUserId) {
    if (requests.findOpenByUserId(user.getId()).isEmpty()) {
      return;
    }
    if (requests.deleteAllOfUser(user.getId()) == 1) {
      recordDeclined(user, actorUserId);
    }
  }

  /**
   * An auditor declines {@code request}: a request of the administration is removed, one of a
   * provider is kept as declined so the same claim does not reopen it at the next sign-in.
   */
  @Transactional
  public void declineByAuditor(AuditorRoleRequest request, User user, UUID auditorId) {
    int ended =
        request.isFromProvider()
            ? requests.markDeclined(request.getId(), clock.instant())
            : requests.deleteAllOfUser(user.getId());
    if (ended == 1) {
      recordDeclined(user, auditorId);
    }
  }

  /**
   * A granted role closes the request that asked for it and is told to every other auditor of the
   * organization.
   */
  @EventListener
  public void onGranted(AuditorRoleGrantedEvent event) {
    User user = event.user();
    requests.deleteAllOfUser(user.getId());
    notifyAuditors(
        user,
        NotificationType.AUDITOR_ROLE_GRANTED,
        "Neue Person in der Revision",
        accountOf(user) + " hat die Rolle Revision erhalten.");
  }

  /** A withdrawn role is told to every remaining auditor, whichever way it went. */
  @EventListener
  public void onRevoked(AuditorRoleRevokedEvent event) {
    User user = event.user();
    notifyAuditors(
        user,
        NotificationType.AUDITOR_ROLE_REVOKED,
        "Rolle Revision entzogen",
        accountOf(user) + " hat die Rolle Revision nicht mehr.");
  }

  /**
   * The administration changed a channel every mail runs through; every auditor of the installation
   * learns of it - SMTP settings and templates hold for all organizations - since a notice or a
   * link by mail is only as trustworthy as these channels.
   */
  @EventListener
  public void onMailConfigurationChanged(MailConfigurationChangedEvent event) {
    notifyEveryAuditor(
        NotificationType.MAIL_CONFIGURATION_CHANGED,
        null,
        "E-Mail-Versand geändert",
        "Die Systemverwaltung hat "
            + event.what()
            + " geändert. Einzelheiten stehen im Revisionsprotokoll.");
  }

  /**
   * A provider was created or its trust settings changed. Whoever sets issuer or signing keys can
   * sign tokens for any subject of the provider, an existing auditor's included; the role grant
   * does not protect against that, so every auditor of the installation - providers hold for all
   * organizations - learns of it at once.
   */
  @EventListener
  public void onIdentityProviderTrustChanged(IdentityProviderTrustChangedEvent event) {
    notifyEveryAuditor(
        NotificationType.IDENTITY_PROVIDER_TRUST_CHANGED,
        event.providerId(),
        event.created() ? "Identitätsanbieter angelegt" : "Identitätsanbieter geändert",
        "Die Systemverwaltung hat den Identitätsanbieter „"
            + event.displayName()
            + (event.created()
                ? "“ angelegt."
                : "“ in Issuer, Client-ID, Signaturschlüsseln, Claims oder Rollenzuordnung"
                    + " geändert.")
            + " Wer diese Angaben bestimmt, kann sich als jedes Konto dieses Anbieters"
            + " anmelden. Einzelheiten stehen im Revisionsprotokoll.");
  }

  private void notifyEveryAuditor(NotificationType type, UUID objectId, String title, String body) {
    for (User auditor : users.findBySystemRole(SystemRole.AUDITOR)) {
      notifications.notify(
          auditor.getOrganizationId(),
          auditor.getId(),
          type,
          AuditObjectType.SYSTEM_SETTING,
          objectId,
          title,
          body);
    }
  }

  private boolean open(User user, UUID requestedByUserId, UUID requestedByProviderId) {
    return requests.insertIfAbsent(
            UUID.randomUUID(),
            user.getOrganizationId(),
            user.getId(),
            requestedByUserId,
            requestedByProviderId,
            clock.instant())
        == 1;
  }

  private String requesterOf(AuditorRoleRequest request) {
    if (request.isFromProvider()) {
      return providers
          .findById(request.getRequestedByProviderId())
          .map(provider -> "Identitätsanbieter „" + provider.getDisplayName() + "“")
          .orElse("Identitätsanbieter");
    }
    return users.findById(request.getRequestedByUserId()).map(AuditorRoles::nameOf).orElse("");
  }

  /** An address the administration changed is flagged with the date it last did. */
  private String addressNote(User user) {
    return localCredentials
        .findById(user.getId())
        .map(LocalCredentials::getEmailChangedByAdminAt)
        .map(
            at ->
                " Die Adresse hat die Systemverwaltung zuletzt am "
                    + DATE.format(at.atZone(clock.getZone()))
                    + " geändert.")
        .orElse("");
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

  /** The name and, where known, the address - the auditor decides about this exact account. */
  private static String accountOf(User user) {
    String name = nameOf(user);
    if (user.getEmail() == null || user.getEmail().isBlank() || name.equals(user.getEmail())) {
      return name;
    }
    return name + " (" + user.getEmail() + ")";
  }

  private static String nameOf(User user) {
    if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
      return user.getDisplayName();
    }
    return user.getEmail() == null ? "ein Konto" : user.getEmail();
  }

  private void recordDeclined(User user, UUID actorUserId) {
    record(
        user,
        actorUserId,
        AuditEventType.AUDITOR_ROLE_REQUEST_DECLINED,
        SystemRole.AUDITOR,
        user.getSystemRole());
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

  private void recordSystem(
      User user, AuditEventType type, SystemRole before, SystemRole after, OidcProvider provider) {
    UUID pseudonym = audit.pseudonymFor(user.getId(), user.getOrganizationId());
    audit.recordSystemProcessAction(
        AuditEvent.builder()
            .organizationId(user.getOrganizationId())
            .actorRef(TokenRoleSynchronizer.IDENTITY_PROVIDER_ACTOR)
            .type(type)
            .object(AuditObjectType.USER_ACCOUNT, pseudonym, null)
            .subject(AuditSubjectKind.USER, user.getId())
            .before(Map.of("role", before.name()))
            .after(Map.of("role", after.name(), "provider", provider.getDisplayName()))
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }
}
