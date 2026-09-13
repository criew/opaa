package io.opaa.auth.local;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.api.types.MailDeliveryPath;
import io.opaa.api.types.ProviderType;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.LocalIssuer;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.auth.local.LocalActionTokenService.IssuedActionToken;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRepository;
import io.opaa.common.ConflictException;
import io.opaa.common.FieldValidationException;
import io.opaa.common.NotFoundException;
import io.opaa.group.GroupMembershipRepository;
import io.opaa.space.Space;
import io.opaa.space.SpaceMembershipRepository;
import io.opaa.space.SpaceRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The account writes of the handover (ADR-0033, Entscheidung 12), one transaction each: starting it
 * on the administration's request, and the redemption the person carries out. It is the second
 * write path of local accounts next to {@link LocalUserService} - for the same reason {@link
 * LocalSelfServiceAccountService} is the third: the act is not an administrative one, and the
 * invariant "a {@code local_credentials} row exists exactly for a {@code users} row of the local
 * issuer" is kept here too, by removing both sides of it at once.
 *
 * <p>No mail and no provider-token verification here - {@link LocalHandoverService} verifies before
 * the transaction opens (a decoder may have to fetch a JWK set) and sends after it committed.
 *
 * <p>The guard of Entscheidung 4 runs at <em>both</em> ends: weeks can pass between the request and
 * the redemption, and it is the redemption that actually takes the local administrator away. The
 * check at the request is the early, honest refusal; the one at the redemption is the binding one.
 */
@Service
public class LocalHandoverAccountService {

  /** The link's lifetime (ADR-0033, Entscheidung 12) - not a setting, unlike the invitation. */
  public static final Duration HANDOVER_TTL = Duration.ofHours(72);

  /** An account already exists under {@code (issuer, subject)}; nothing is ever merged. */
  public static final String PROVIDER_ACCOUNT_EXISTS = "PROVIDER_ACCOUNT_EXISTS";

  /** The presented provider token belongs to another provider than the handover names. */
  public static final String PROVIDER_MISMATCH = "PROVIDER_MISMATCH";

  static final String PROVIDER_FIELD = "providerId";
  static final String PROVIDER_NOT_ELIGIBLE = "NOT_ELIGIBLE";
  static final String REASON_FIELD = "reason";
  static final String PROVIDER_ACCOUNT_EXISTS_MESSAGE =
      "Unter dieser Anbieteridentität besteht bereits ein Konto. Eine Zusammenführung zweier"
          + " Konten gibt es nicht.";
  static final String PROVIDER_MISMATCH_MESSAGE =
      "Die Anmeldung erfolgte bei einem anderen Anbieter als dem, für den die Übergabe angestoßen"
          + " wurde.";
  static final String BOOTSTRAP_MESSAGE =
      "Das Notanker-Konto der Systemverwaltung kann nicht übergeben werden.";

  private final UserRepository users;
  private final LocalCredentialsRepository credentials;
  private final LocalActionTokenService actionTokens;
  private final LocalActionTokenRepository actionTokenRows;
  private final LocalRefreshTokenRepository refreshTokens;
  private final LocalRevokedTokenRepository revokedTokens;
  private final LocalAdminAvailabilityGuard adminGuard;
  private final OidcProviderRepository providers;
  private final SpaceRepository spaces;
  private final SpaceMembershipRepository spaceMemberships;
  private final GroupMembershipRepository groupMemberships;
  private final AuditEventRecorder audit;
  private final Clock clock;

  public LocalHandoverAccountService(
      UserRepository users,
      LocalCredentialsRepository credentials,
      LocalActionTokenService actionTokens,
      LocalActionTokenRepository actionTokenRows,
      LocalRefreshTokenRepository refreshTokens,
      LocalRevokedTokenRepository revokedTokens,
      LocalAdminAvailabilityGuard adminGuard,
      OidcProviderRepository providers,
      SpaceRepository spaces,
      SpaceMembershipRepository spaceMemberships,
      GroupMembershipRepository groupMemberships,
      AuditEventRecorder audit,
      Clock clock) {
    this.users = users;
    this.credentials = credentials;
    this.actionTokens = actionTokens;
    this.actionTokenRows = actionTokenRows;
    this.refreshTokens = refreshTokens;
    this.revokedTokens = revokedTokens;
    this.adminGuard = adminGuard;
    this.providers = providers;
    this.spaces = spaces;
    this.spaceMemberships = spaceMemberships;
    this.groupMemberships = groupMemberships;
    this.audit = audit;
    this.clock = clock;
  }

  /** What a handover moves - the person's own content, as counts (ADR-0033, Entscheidung 12). */
  public record HandoverScope(
      String personalSpaceName,
      long spaceMemberships,
      long groupMemberships,
      SystemRole systemRole) {}

  /** A started handover: the account, the chosen provider and the one secret to deliver. */
  public record HandoverStarted(User user, OidcProvider provider, IssuedActionToken token) {
    @Override
    public String toString() {
      return "HandoverStarted[user=" + user.getId() + ", provider=" + provider.getId() + "]";
    }
  }

  /** What the redemption page shows before the irreversible step. */
  public record HandoverPreview(
      User user, OidcProvider provider, String reason, Instant expiresAt, HandoverScope scope) {}

  /** A redeemed handover - the account as it now is, under the provider identity. */
  public record HandedOver(User user, OidcProvider provider, HandoverScope scope) {}

  /**
   * Starts the handover: validates the reason and the provider, refuses the emergency-anchor
   * account and a last login-capable administrator, and issues the single-use code.
   */
  @Transactional
  public HandoverStarted start(CurrentUser actor, UUID userId, UUID providerId, String reason) {
    String checkedReason =
        LocalUserService.requireText(
            REASON_FIELD, reason, LocalCredentials.CREATED_REASON_MAX_LENGTH);
    LocalUserOverview current = load(actor.organizationId(), userId);
    if (current.credentials().isBootstrap()) {
      throw new ConflictException(BOOTSTRAP_MESSAGE, LocalUserService.BOOTSTRAP_ACCOUNT);
    }
    OidcProvider provider = requireEligibleProvider(providerId);
    if (current.user().getSystemRole() == SystemRole.SYSTEM_ADMIN) {
      adminGuard.requireAnotherLoginCapableAdmin(actor.organizationId(), userId);
    }
    IssuedActionToken token =
        actionTokens.issueHandover(userId, HANDOVER_TTL, provider.getId(), checkedReason);
    return new HandoverStarted(current.user(), provider, token);
  }

  /** The delivery path of the handover link, audited once the send has happened (after commit). */
  @Transactional
  public void recordHandoverRequested(
      CurrentUser actor, User user, UUID providerId, MailDeliveryPath path) {
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("providerId", providerId.toString());
    after.put("deliveryPath", path.name());
    recordOnSubject(actor.id(), user, AuditEventType.LOCAL_USER_HANDOVER_REQUESTED, after);
  }

  /** What the code stands for, without consuming it; the same 400 for every refused code. */
  @Transactional(readOnly = true)
  public HandoverPreview preview(String rawToken) {
    LocalActionToken token = redeemableToken(rawToken);
    User user = localAccount(token.getUserId());
    OidcProvider provider =
        providers
            .findById(token.getProviderId())
            .orElseThrow(LocalActionTokenService::invalidToken);
    return new HandoverPreview(
        user, provider, token.getReason(), token.getExpiresAt(), scopeOf(user));
  }

  /**
   * The redemption, in one transaction: the code is consumed atomically (a second call finds
   * nothing), the identity is rewritten to {@code (issuer, subject)} of the person's own token, the
   * credentials and every link and denylist row of the account are deleted and every refresh family
   * is revoked as {@code HANDED_OVER}. Refuses when an account already exists under that identity
   * (409, never a merge), for the emergency-anchor account and for the last login-capable system
   * administrator.
   *
   * @param subject the {@code sub} of the verified provider token - never an input of the
   *     administration
   */
  @Transactional
  public HandedOver redeem(String rawToken, OidcProvider provider, String subject) {
    Instant now = clock.instant();
    LocalActionToken token =
        actionTokens
            .redeem(rawToken, ActionTokenPurpose.HANDOVER)
            .orElseThrow(LocalActionTokenService::invalidToken);
    if (!provider.getId().equals(token.getProviderId())) {
      throw new ConflictException(PROVIDER_MISMATCH_MESSAGE, PROVIDER_MISMATCH);
    }
    User user = localAccount(token.getUserId());
    LocalCredentials row =
        credentials.findById(user.getId()).orElseThrow(LocalActionTokenService::invalidToken);
    if (row.isBootstrap()) {
      throw new ConflictException(BOOTSTRAP_MESSAGE, LocalUserService.BOOTSTRAP_ACCOUNT);
    }
    String issuer = provider.getIssuerUri();
    if (users.findBySubjectAndIssuer(subject, issuer).isPresent()) {
      throw new ConflictException(PROVIDER_ACCOUNT_EXISTS_MESSAGE, PROVIDER_ACCOUNT_EXISTS);
    }
    if (user.getSystemRole() == SystemRole.SYSTEM_ADMIN) {
      adminGuard.requireAnotherLoginCapableAdmin(user.getOrganizationId(), user.getId());
    }
    HandoverScope scope = scopeOf(user);
    user.handOverTo(issuer, subject);
    try {
      user = users.saveAndFlush(user);
    } catch (DataIntegrityViolationException raceLost) {
      // two redemptions for the same identity raced past the check: users_subject_issuer_unique
      throw new ConflictException(PROVIDER_ACCOUNT_EXISTS_MESSAGE, PROVIDER_ACCOUNT_EXISTS);
    }
    int revoked = refreshTokens.revokeAllForUser(user.getId(), RevocationReason.HANDED_OVER, now);
    actionTokenRows.deleteAllByUserId(user.getId());
    revokedTokens.deleteAllByUserId(user.getId());
    credentials.deleteById(user.getId());
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("providerId", provider.getId().toString());
    after.put("systemRole", scope.systemRole().name());
    after.put("personalSpace", scope.personalSpaceName() == null ? 0 : 1);
    after.put("spaceMemberships", scope.spaceMemberships());
    after.put("groupMemberships", scope.groupMemberships());
    after.put("revokedSessions", revoked);
    // The person is the actor: they hold the code and completed the act themselves. The subject of
    // the new identity never appears - ADR-0033, Entscheidung 13.
    recordOnSubject(user.getId(), user, AuditEventType.LOCAL_USER_HANDED_OVER, after);
    if (revoked > 0) {
      recordOnSubject(
          user.getId(),
          user,
          AuditEventType.LOCAL_SESSION_REVOKED,
          Map.of("reason", RevocationReason.HANDED_OVER.name()));
    }
    return new HandedOver(user, provider, scope);
  }

  private HandoverScope scopeOf(User user) {
    String personalSpace =
        spaces.findByOwnerId(user.getId()).stream()
            .filter(Space::isDefault)
            .map(Space::getName)
            .findFirst()
            .orElse(null);
    return new HandoverScope(
        personalSpace,
        spaceMemberships.countByUserId(user.getId()),
        groupMemberships.countByUserId(user.getId()),
        user.getSystemRole());
  }

  private LocalActionToken redeemableToken(String rawToken) {
    return actionTokens
        .findRedeemable(rawToken, ActionTokenPurpose.HANDOVER)
        .orElseThrow(LocalActionTokenService::invalidToken);
  }

  /** A code whose account is no longer a local one is as invalid as an expired one. */
  private User localAccount(UUID userId) {
    return users
        .findById(userId)
        .filter(found -> LocalIssuer.URN.equals(found.getIssuer()))
        .orElseThrow(LocalActionTokenService::invalidToken);
  }

  private OidcProvider requireEligibleProvider(UUID providerId) {
    Optional<OidcProvider> provider =
        providers
            .findById(providerId)
            .filter(row -> row.getProviderType() == ProviderType.OIDC)
            .filter(OidcProvider::isEnabled);
    return provider.orElseThrow(
        () ->
            FieldValidationException.of(
                PROVIDER_FIELD,
                PROVIDER_NOT_ELIGIBLE,
                "Wählen Sie einen aktivierten Identitätsanbieter."));
  }

  private LocalUserOverview load(UUID organizationId, UUID userId) {
    User user =
        users
            .findByIdAndOrganizationId(userId, organizationId)
            .filter(found -> LocalIssuer.URN.equals(found.getIssuer()))
            .orElseThrow(() -> new NotFoundException(LocalUserService.NOT_FOUND_MESSAGE));
    LocalCredentials row =
        credentials
            .findById(userId)
            .orElseThrow(() -> new NotFoundException(LocalUserService.NOT_FOUND_MESSAGE));
    return LocalUserOverview.of(user, row, clock.instant());
  }

  private void recordOnSubject(
      UUID actorId, User subject, AuditEventType type, Map<String, Object> after) {
    UUID pseudonym = audit.pseudonymFor(subject.getId(), subject.getOrganizationId());
    audit.recordUserActionOnSubject(
        AuditEvent.builder()
            .organizationId(subject.getOrganizationId())
            .actor(actorId)
            .type(type)
            .object(AuditObjectType.USER_ACCOUNT, pseudonym, null)
            .subject(AuditSubjectKind.USER, subject.getId())
            .after(after)
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }
}
