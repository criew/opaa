package io.opaa.auth.local;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.api.types.LocalAccountState;
import io.opaa.api.types.LockReason;
import io.opaa.api.types.MailDeliveryPath;
import io.opaa.api.types.PasswordChangeReason;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.LocalIssuer;
import io.opaa.auth.User;
import io.opaa.auth.UserProvisionedEvent;
import io.opaa.auth.UserRepository;
import io.opaa.auth.UserService;
import io.opaa.auth.local.LocalActionTokenService.IssuedActionToken;
import io.opaa.common.ConflictException;
import io.opaa.common.FieldValidationException;
import io.opaa.common.FieldValidationException.FieldError;
import io.opaa.common.NotFoundException;
import io.opaa.security.PasswordGenerator;
import io.opaa.space.Space;
import io.opaa.space.SpaceRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single write path of local accounts (ADR-0033, Entscheidungen 3, 4, 11 and 13): a {@code
 * local_credentials} row exists exactly for a {@code users} row of the local issuer, and every act
 * of administration - creation, change, lock, unlock, reset, generated password, deletion - is one
 * transaction here, audited under the acting person with the account as a pseudonym and never with
 * an address, a name or the creation reason as a value. Mail is not sent here: the orchestrating
 * {@link LocalUserAdminService} sends after the commit.
 *
 * <p>Every act that could remove the last login-capable system administrator - locking, expiring,
 * withdrawing the role from or deleting a {@code SYSTEM_ADMIN} - runs through {@link
 * LocalAdminAvailabilityGuard} inside the same transaction, so the guard's lock holds until the
 * change commits.
 */
@Service
public class LocalUserService {

  public static final String EMAIL_TAKEN = "EMAIL_TAKEN";
  public static final String SELF_LOCKOUT = "SELF_LOCKOUT";
  public static final String SELF_DELETE = "SELF_DELETE";
  public static final String ALREADY_LOCKED = "ALREADY_LOCKED";
  public static final String NOT_LOCKED = "NOT_LOCKED";
  public static final String BOOTSTRAP_ACCOUNT = "BOOTSTRAP_ACCOUNT";
  public static final String ACCOUNT_OWNS_CONTENT = "ACCOUNT_OWNS_CONTENT";

  static final String NOT_FOUND_MESSAGE = "Lokales Konto nicht gefunden";
  static final String INVALID_ADDRESS = "INVALID_ADDRESS";
  static final String REQUIRED = "REQUIRED";
  static final String TOO_LONG = "TOO_LONG";
  static final String IN_THE_PAST = "IN_THE_PAST";

  private static final Logger log = LoggerFactory.getLogger(LocalUserService.class);

  private final UserRepository users;
  private final LocalCredentialsRepository credentials;
  private final LocalAuthSettingsRepository settings;
  private final LocalActionTokenService actionTokens;
  private final LocalRefreshTokenRepository refreshTokens;
  private final LocalAdminAvailabilityGuard adminGuard;
  private final UserService userService;
  private final PasswordEncoder passwordEncoder;
  private final PasswordGenerator passwordGenerator;
  private final SpaceRepository spaces;
  private final AuditEventRecorder audit;
  private final ApplicationEventPublisher events;
  private final Clock clock;

  public LocalUserService(
      UserRepository users,
      LocalCredentialsRepository credentials,
      LocalAuthSettingsRepository settings,
      LocalActionTokenService actionTokens,
      LocalRefreshTokenRepository refreshTokens,
      LocalAdminAvailabilityGuard adminGuard,
      UserService userService,
      PasswordEncoder passwordEncoder,
      PasswordGenerator passwordGenerator,
      SpaceRepository spaces,
      AuditEventRecorder audit,
      ApplicationEventPublisher events,
      Clock clock) {
    this.users = users;
    this.credentials = credentials;
    this.settings = settings;
    this.actionTokens = actionTokens;
    this.refreshTokens = refreshTokens;
    this.adminGuard = adminGuard;
    this.userService = userService;
    this.passwordEncoder = passwordEncoder;
    this.passwordGenerator = passwordGenerator;
    this.spaces = spaces;
    this.audit = audit;
    this.events = events;
    this.clock = clock;
  }

  // ---- reads

  /** Every local account of the organization; accounts of an OIDC provider never appear. */
  @Transactional(readOnly = true)
  public List<LocalUserOverview> allOf(UUID organizationId) {
    Instant now = clock.instant();
    List<User> localUsers = users.findByOrganizationIdAndIssuer(organizationId, LocalIssuer.URN);
    Map<UUID, LocalCredentials> rows =
        credentials.findAllById(localUsers.stream().map(User::getId).toList()).stream()
            .collect(Collectors.toMap(LocalCredentials::getUserId, Function.identity()));
    return localUsers.stream()
        .filter(user -> rows.containsKey(user.getId()))
        .map(user -> LocalUserOverview.of(user, rows.get(user.getId()), now))
        .toList();
  }

  /** 404 for an unknown id, an account of another organization and an OIDC account alike. */
  @Transactional(readOnly = true)
  public LocalUserOverview get(UUID organizationId, UUID userId) {
    return load(organizationId, userId);
  }

  @Transactional(readOnly = true)
  public LocalAuthSettings.Values settings() {
    return settings
        .findSingleton()
        .map(LocalAuthSettings::values)
        .orElseGet(LocalAuthSettings.Values::defaults);
  }

  // ---- creation

  /** The account plus the one secret to hand out: the invitation link or the initial password. */
  public record CreatedAccount(
      User user,
      LocalCredentials credentials,
      IssuedActionToken invitation,
      String initialPassword) {

    @Override
    public String toString() {
      return "CreatedAccount[user=" + user.getId() + ", invited=" + (invitation != null) + "]";
    }
  }

  @Transactional
  public CreatedAccount create(CurrentUser actor, LocalUserCreation creation) {
    Instant now = clock.instant();
    LocalAuthSettings.Values policy = settings();
    String email = requireAddress(creation.email());
    String displayName = requireText("displayName", creation.displayName(), 255);
    String createdReason =
        requireText(
            "createdReason", creation.createdReason(), LocalCredentials.CREATED_REASON_MAX_LENGTH);
    Instant expiresAt =
        resolveExpiry(creation.expiresAt(), creation.noExpiry(), policy.defaultExpiryDays(), now);
    requireAddressFree(email, null);

    User user = User.localAccount(email, displayName);
    user.setOrganizationId(actor.organizationId());
    user.setLastLoginAt(null);
    try {
      user = users.saveAndFlush(user);
    } catch (DataIntegrityViolationException raceLost) {
      // two requests with the same address raced past the check: ux_users_local_email decided
      throw addressTaken();
    }
    LocalCredentials row = new LocalCredentials(user.getId(), createdReason, now);
    row.markEmailVerified(now);
    row.setExpiresAt(expiresAt, now);
    String initialPassword = null;
    if (!creation.invite()) {
      initialPassword = passwordGenerator.generate();
      row.setPasswordHash(passwordEncoder.encode(initialPassword), now);
      row.requirePasswordChange(PasswordChangeReason.INITIAL, now);
    }
    row = credentials.save(row);
    IssuedActionToken invitation =
        creation.invite()
            ? actionTokens.issue(
                user.getId(),
                ActionTokenPurpose.SET_PASSWORD,
                Duration.ofHours(policy.invitationTokenTtlHours()))
            : null;
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("mode", creation.invite() ? "INVITE" : "INITIAL_PASSWORD");
    after.put("expiresAt", expiryValue(expiresAt));
    recordAdminAct(actor, user, AuditEventType.LOCAL_USER_CREATED, null, after);
    if (creation.systemRole() != null && creation.systemRole() != SystemRole.USER) {
      user = userService.updateRole(user.getId(), creation.systemRole(), actor);
    }
    // the personal space, on the same path every sign-in takes (after the commit)
    events.publishEvent(UserProvisionedEvent.withoutTokenGroups(user, true));
    return new CreatedAccount(user, row, invitation, initialPassword);
  }

  /** The delivery path of a link is audited once the send has happened, after the commit. */
  @Transactional
  public void recordLinkDelivery(
      CurrentUser actor, User user, AuditEventType type, MailDeliveryPath path) {
    recordAdminAct(actor, user, type, null, Map.of("deliveryPath", path.name()));
  }

  // ---- change

  @Transactional
  public LocalUserOverview update(CurrentUser actor, UUID userId, LocalUserUpdate update) {
    Instant now = clock.instant();
    LocalUserOverview current = load(actor.organizationId(), userId);
    User user = current.user();
    LocalCredentials row = current.credentials();
    List<String> changed = new ArrayList<>();
    Map<String, Object> before = new LinkedHashMap<>();
    Map<String, Object> after = new LinkedHashMap<>();

    if (update.email() != null) {
      String email = requireAddress(update.email());
      if (!email.equalsIgnoreCase(user.getEmail())) {
        requireAddressFree(email, user.getId());
        user.setEmail(email);
        // a link mailed to the old address must not set the password of the new one
        closeOpenLinks(user.getId());
        changed.add("email");
      }
    }
    if (update.displayName() != null) {
      String displayName = requireText("displayName", update.displayName(), 255);
      if (!displayName.equals(user.getDisplayName())) {
        user.setDisplayName(displayName);
        changed.add("displayName");
      }
    }
    if (update.createdReason() != null) {
      String reason =
          requireText(
              "createdReason", update.createdReason(), LocalCredentials.CREATED_REASON_MAX_LENGTH);
      if (!reason.equals(row.getCreatedReason())) {
        row.setCreatedReason(reason, now);
        changed.add("createdReason");
      }
    }
    if (update.noExpiry() || update.expiresAt() != null) {
      Instant target = update.noExpiry() ? null : update.expiresAt();
      if (!java.util.Objects.equals(target, row.getExpiresAt())) {
        boolean expiresNow = target != null && !target.isAfter(now);
        if (expiresNow && userId.equals(actor.id())) {
          // an expiry in the past is a lock by another name
          throw new ConflictException(
              "Das eigene Konto kann nicht abgelaufen werden.", SELF_LOCKOUT);
        }
        if (expiresNow && user.getSystemRole() == SystemRole.SYSTEM_ADMIN) {
          adminGuard.requireAnotherLoginCapableAdmin(actor.organizationId(), user.getId());
        }
        before.put("expiresAt", expiryValue(row.getExpiresAt()));
        after.put("expiresAt", expiryValue(target));
        row.setExpiresAt(target, now);
        changed.add("expiresAt");
      }
    }
    if (!changed.isEmpty()) {
      users.save(user);
      credentials.save(row);
      after.put("changedFields", changed);
      recordAdminAct(
          actor, user, AuditEventType.LOCAL_USER_CHANGED, before.isEmpty() ? null : before, after);
    }
    if (update.systemRole() != null && update.systemRole() != user.getSystemRole()) {
      userService.updateRole(user.getId(), update.systemRole(), actor);
    }
    return load(actor.organizationId(), userId);
  }

  // ---- lock and unlock

  @Transactional
  public LocalUserOverview lock(CurrentUser actor, UUID userId) {
    LocalUserOverview current = load(actor.organizationId(), userId);
    if (userId.equals(actor.id())) {
      throw new ConflictException("Das eigene Konto kann nicht gesperrt werden.", SELF_LOCKOUT);
    }
    LocalCredentials row = current.credentials();
    if (row.getLockedAt() != null && row.getLockedReason() != LockReason.FAILED_LOGINS) {
      throw new ConflictException("Das Konto ist bereits gesperrt.", ALREADY_LOCKED);
    }
    if (current.user().getSystemRole() == SystemRole.SYSTEM_ADMIN) {
      adminGuard.requireAnotherLoginCapableAdmin(actor.organizationId(), userId);
    }
    applyLock(current.user(), row, LockReason.ADMIN, actor, null);
    return load(actor.organizationId(), userId);
  }

  /**
   * The lock of the daily run (ADR-0033, Entscheidung 9), under the {@code local-auth} system
   * actor. A {@link ConflictException} means the account is the last login-capable system
   * administrator and stays untouched.
   */
  @Transactional
  public void lockForInactivity(UUID userId) {
    User user = users.findById(userId).orElseThrow(() -> new NotFoundException(NOT_FOUND_MESSAGE));
    LocalCredentials row =
        credentials.findById(userId).orElseThrow(() -> new NotFoundException(NOT_FOUND_MESSAGE));
    if (user.getSystemRole() == SystemRole.SYSTEM_ADMIN) {
      adminGuard.requireAnotherLoginCapableAdmin(user.getOrganizationId(), userId);
    }
    applyLock(user, row, LockReason.INACTIVITY, null, LocalRefreshTokenService.SYSTEM_ACTOR);
  }

  private void applyLock(
      User user, LocalCredentials row, LockReason reason, CurrentUser actor, String systemActor) {
    Instant now = clock.instant();
    row.unlock(now);
    row.lock(reason, now, null);
    row.invalidateSessionsIssuedBefore(LocalTokenRevocationService.cutoffFor(now), now);
    credentials.save(row);
    closeOpenLinks(user.getId());
    int revoked =
        refreshTokens.revokeAllForUser(user.getId(), RevocationReason.ACCOUNT_LOCKED, now);
    Map<String, Object> after = Map.of("reason", reason.name());
    if (actor != null) {
      recordAdminAct(actor, user, AuditEventType.LOCAL_USER_LOCKED, null, after);
    } else {
      recordSystemAct(systemActor, user, AuditEventType.LOCAL_USER_LOCKED, after);
    }
    if (revoked > 0) {
      Map<String, Object> revocation = Map.of("reason", RevocationReason.ACCOUNT_LOCKED.name());
      if (actor != null) {
        recordAdminAct(actor, user, AuditEventType.LOCAL_SESSION_REVOKED, null, revocation);
      } else {
        recordSystemAct(systemActor, user, AuditEventType.LOCAL_SESSION_REVOKED, revocation);
      }
    }
  }

  @Transactional
  public LocalUserOverview unlock(CurrentUser actor, UUID userId) {
    Instant now = clock.instant();
    LocalUserOverview current = load(actor.organizationId(), userId);
    LocalCredentials row = current.credentials();
    if (current.state() != LocalAccountState.LOCKED && row.getFailedLoginAttempts() == 0) {
      // an expired failed-login lockout leaves locked_at as a trace but nothing to lift
      throw new ConflictException("Das Konto ist nicht gesperrt.", NOT_LOCKED);
    }
    Map<String, Object> before =
        row.getLockedReason() == null ? null : Map.of("lockedReason", row.getLockedReason().name());
    row.unlock(now);
    credentials.save(row);
    recordAdminAct(actor, current.user(), AuditEventType.LOCAL_USER_UNLOCKED, before, null);
    return load(actor.organizationId(), userId);
  }

  // ---- password

  /** A reset link, or - for an account that has no password yet - the invitation again. */
  public record ResetLinkIssued(User user, IssuedActionToken token, boolean invitation) {
    @Override
    public String toString() {
      return "ResetLinkIssued[user=" + user.getId() + ", invitation=" + invitation + "]";
    }
  }

  @Transactional
  public ResetLinkIssued issueResetLink(CurrentUser actor, UUID userId) {
    Instant now = clock.instant();
    LocalUserOverview current = load(actor.organizationId(), userId);
    LocalCredentials row = current.credentials();
    LocalAuthSettings.Values policy = settings();
    boolean invitation = row.getPasswordHash() == null;
    IssuedActionToken token =
        invitation
            ? actionTokens.issue(
                userId,
                ActionTokenPurpose.SET_PASSWORD,
                Duration.ofHours(policy.invitationTokenTtlHours()))
            : actionTokens.issue(
                userId,
                ActionTokenPurpose.RESET_PASSWORD,
                Duration.ofMinutes(policy.resetTokenTtlMinutes()));
    if (!invitation) {
      row.invalidateSessionsIssuedBefore(LocalTokenRevocationService.cutoffFor(now), now);
      credentials.save(row);
      recordSessionsRevoked(actor, current.user(), now);
    }
    return new ResetLinkIssued(current.user(), token, invitation);
  }

  /** Returns the generated password - the one time it exists in clear text. */
  @Transactional
  public String generatePassword(CurrentUser actor, UUID userId) {
    Instant now = clock.instant();
    LocalUserOverview current = load(actor.organizationId(), userId);
    LocalCredentials row = current.credentials();
    boolean first = row.getPasswordHash() == null;
    String password = passwordGenerator.generate();
    row.setPasswordHash(passwordEncoder.encode(password), now);
    row.requirePasswordChange(
        first ? PasswordChangeReason.INITIAL : PasswordChangeReason.ADMIN_RESET, now);
    if (row.getLockedReason() == LockReason.FAILED_LOGINS) {
      row.unlock(now);
    } else {
      row.resetFailedLoginAttempts(now);
    }
    row.invalidateSessionsIssuedBefore(LocalTokenRevocationService.cutoffFor(now), now);
    credentials.save(row);
    closeOpenLinks(userId);
    recordAdminAct(actor, current.user(), AuditEventType.LOCAL_USER_PASSWORD_GENERATED, null, null);
    recordSessionsRevoked(actor, current.user(), now);
    return password;
  }

  private void recordSessionsRevoked(CurrentUser actor, User user, Instant now) {
    int revoked = refreshTokens.revokeAllForUser(user.getId(), RevocationReason.ADMIN_RESET, now);
    if (revoked > 0) {
      recordAdminAct(
          actor,
          user,
          AuditEventType.LOCAL_SESSION_REVOKED,
          null,
          Map.of("reason", RevocationReason.ADMIN_RESET.name()));
    }
  }

  // ---- deletion

  /**
   * Deletes an account that nothing references (ADR-0033, Entscheidung 11): no knowledge library,
   * no space besides the personal one, no chat - and no row of the rights and evidence tables that
   * point at a user with {@code ON DELETE RESTRICT} (group membership history, grant history,
   * grants, space associations, incident scopes, impersonation grants). In practice that is an
   * account that was never used; every other one is locked, not deleted. The personal space goes
   * first, audited as {@code SPACE_DELETED} like any space deletion ({@code
   * fk_spaces_owner_organization} is RESTRICT and leaves no choice); credentials, tokens,
   * memberships and the pseudonym mapping follow by the schema's cascades. The refusal names the
   * blocking tables in the log only - the response says "referenced", nothing more.
   */
  @Transactional
  public void delete(CurrentUser actor, UUID userId) {
    LocalUserOverview current = load(actor.organizationId(), userId);
    User user = current.user();
    if (userId.equals(actor.id())) {
      throw new ConflictException("Das eigene Konto kann nicht gelöscht werden.", SELF_DELETE);
    }
    if (current.credentials().isBootstrap()) {
      throw new ConflictException(
          "Das Notanker-Konto der Systemverwaltung kann nicht gelöscht werden.", BOOTSTRAP_ACCOUNT);
    }
    if (user.getSystemRole() == SystemRole.SYSTEM_ADMIN) {
      adminGuard.requireAnotherLoginCapableAdmin(actor.organizationId(), userId);
    }
    List<String> blockers = blockers(users.countDeletionBlockers(userId));
    if (!blockers.isEmpty()) {
      log.info("Local account {} is not deleted: still referenced by {}", userId, blockers);
      throw stillReferenced();
    }
    Map<String, Object> before = new LinkedHashMap<>();
    before.put("systemRole", user.getSystemRole().name());
    before.put("state", current.state().name());
    recordAdminAct(actor, user, AuditEventType.LOCAL_USER_DELETED, before, null);
    List<Space> personal = spaces.findByOwnerId(userId);
    for (Space space : personal) {
      audit.recordUserAction(
          AuditEvent.builder()
              .organizationId(space.getOrganizationId())
              .actor(actor.id())
              .type(AuditEventType.SPACE_DELETED)
              .object(AuditObjectType.SPACE, space.getId(), space.getName())
              .before(spaceAuditPayload(space))
              .outcome(AuditOutcome.SUCCESS)
              .build());
    }
    try {
      spaces.deleteAll(personal);
      users.delete(user);
      users.flush();
    } catch (DataIntegrityViolationException referencedMeanwhile) {
      log.info("Local account {} is not deleted: a reference appeared during the deletion", userId);
      throw stillReferenced();
    }
  }

  private static List<String> blockers(UserRepository.DeletionBlockers counts) {
    List<String> blockers = new ArrayList<>();
    if (counts.getLibraries() > 0) {
      blockers.add("knowledge_libraries");
    }
    if (counts.getSpaces() > 0) {
      blockers.add("spaces");
    }
    if (counts.getChats() > 0) {
      blockers.add("chats");
    }
    if (counts.getGroupHistory() > 0) {
      blockers.add("group_membership_history");
    }
    if (counts.getGrantHistory() > 0) {
      blockers.add("asset_grant_history");
    }
    if (counts.getGrants() > 0) {
      blockers.add("asset_grants");
    }
    if (counts.getAssociations() > 0) {
      blockers.add("space_asset_associations");
    }
    if (counts.getIncidentScopes() > 0) {
      blockers.add("audit_incident_scope_grants");
    }
    if (counts.getImpersonationGrants() > 0) {
      blockers.add("diagnostic_impersonation_grants");
    }
    return blockers;
  }

  private static ConflictException stillReferenced() {
    return new ConflictException(
        "Das Konto ist noch in Inhalts-, Rechte- oder Nachweisbeständen referenziert und kann"
            + " deshalb nicht gelöscht werden. Sperren Sie es stattdessen.",
        ACCOUNT_OWNS_CONTENT);
  }

  /** The same payload {@code SpaceService} writes for a space deletion. */
  private static Map<String, Object> spaceAuditPayload(Space space) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("name", space.getName());
    payload.put("visibility", space.getVisibility().name());
    payload.put("ownerId", space.getOwnerId().toString());
    return payload;
  }

  /** Every open invitation or reset link of the account is closed - see the callers. */
  private void closeOpenLinks(UUID userId) {
    actionTokens.consumeOpen(userId, ActionTokenPurpose.SET_PASSWORD);
    actionTokens.consumeOpen(userId, ActionTokenPurpose.RESET_PASSWORD);
  }

  // ---- helpers

  private LocalUserOverview load(UUID organizationId, UUID userId) {
    User user =
        users
            .findByIdAndOrganizationId(userId, organizationId)
            .filter(found -> LocalIssuer.URN.equals(found.getIssuer()))
            .orElseThrow(() -> new NotFoundException(NOT_FOUND_MESSAGE));
    LocalCredentials row =
        credentials.findById(userId).orElseThrow(() -> new NotFoundException(NOT_FOUND_MESSAGE));
    return LocalUserOverview.of(user, row, clock.instant());
  }

  private void requireAddressFree(String email, UUID self) {
    Optional<User> taken = users.findByIssuerAndEmailIgnoreCase(LocalIssuer.URN, email);
    if (taken.isPresent() && !taken.get().getId().equals(self)) {
      throw addressTaken();
    }
  }

  private static ConflictException addressTaken() {
    return new ConflictException(
        "Diese E-Mail-Adresse kann für ein lokales Konto nicht verwendet werden.", EMAIL_TAKEN);
  }

  private static String requireAddress(String email) {
    String trimmed = email == null ? "" : email.trim();
    int at = trimmed.indexOf('@');
    boolean plausible =
        at > 0
            && at < trimmed.length() - 1
            && trimmed.indexOf('@', at + 1) < 0
            && trimmed.length() <= 320
            && trimmed.chars().noneMatch(Character::isWhitespace);
    if (!plausible) {
      throw FieldValidationException.of(
          "email", INVALID_ADDRESS, "Bitte geben Sie eine gültige E-Mail-Adresse an.");
    }
    return trimmed;
  }

  private static String requireText(String field, String value, int maxLength) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.isEmpty()) {
      throw new FieldValidationException(
          "Bitte füllen Sie das Feld aus.",
          List.of(new FieldError(field, REQUIRED, "Das Feld darf nicht leer sein.")));
    }
    if (trimmed.length() > maxLength) {
      throw new FieldValidationException(
          "Die Eingabe ist zu lang.",
          List.of(
              new FieldError(
                  field, TOO_LONG, "Höchstens " + maxLength + " Zeichen sind erlaubt.")));
    }
    return trimmed;
  }

  /**
   * The audit value of an expiry date; a payload map allows no null (ADR-0033, Entscheidung 13).
   */
  private static String expiryValue(Instant expiresAt) {
    return expiresAt == null ? "NONE" : expiresAt.toString();
  }

  private static Instant resolveExpiry(
      Instant requested, boolean noExpiry, int defaultExpiryDays, Instant now) {
    if (noExpiry) {
      return null;
    }
    if (requested == null) {
      return now.plus(Duration.ofDays(defaultExpiryDays));
    }
    if (!requested.isAfter(now)) {
      throw FieldValidationException.of(
          "expiresAt", IN_THE_PAST, "Das Ablaufdatum muss in der Zukunft liegen.");
    }
    return requested;
  }

  private void recordAdminAct(
      CurrentUser actor,
      User subject,
      AuditEventType type,
      Map<String, Object> before,
      Map<String, Object> after) {
    UUID pseudonym = audit.pseudonymFor(subject.getId(), subject.getOrganizationId());
    audit.recordUserActionOnSubject(
        AuditEvent.builder()
            .organizationId(subject.getOrganizationId())
            .actor(actor.id())
            .type(type)
            .object(AuditObjectType.USER_ACCOUNT, pseudonym, null)
            .subject(AuditSubjectKind.USER, subject.getId())
            .before(before)
            .after(after)
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }

  private void recordSystemAct(
      String actorRef, User subject, AuditEventType type, Map<String, Object> after) {
    UUID pseudonym = audit.pseudonymFor(subject.getId(), subject.getOrganizationId());
    audit.recordSystemProcessAction(
        AuditEvent.builder()
            .organizationId(subject.getOrganizationId())
            .actorRef(actorRef)
            .type(type)
            .object(AuditObjectType.USER_ACCOUNT, pseudonym, null)
            .subject(AuditSubjectKind.USER, subject.getId())
            .after(after)
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }
}
