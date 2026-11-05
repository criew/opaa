package io.opaa.externalaccess.token;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.ExternalAccessTokenStatus;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.NotFoundException;
import io.opaa.externalaccess.token.ExternalAccessTokenService.SelectedLibrary;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Bestandsliste of every access token and the two blocking levers of the Systemverwaltung
 * (ADR-0035, Entscheidung 2).
 *
 * <p>What this class deliberately cannot do is as much its contract as what it can: it never
 * returns a token value, never a usage count and never a usage date, and it offers no filter, no
 * sorting and no output by person. The promise of ADR-0068 (decision 2) is that no surface filters,
 * groups or sorts usage data by person - a list filterable by person that carried a usage date
 * would be exactly that. For the decision this list serves, the value is not needed: blocking
 * follows an incident or an account lifecycle, never disuse. Filtering is therefore over state and
 * expiry only, and the order is fixed at "soonest expiry first".
 *
 * <p>The Sperre je Person is a write taking an owner id, not a filter over the list.
 */
@Service
public class ExternalAccessTokenAdminService {

  /** The cap of the unpaged Bestandsliste; see {@link #list}. */
  static final int MAX_ROWS = 2_000;

  private final ExternalAccessTokenRepository tokens;
  private final ServiceAccountTokenRepository serviceAccountTokens;
  private final ServiceAccountRepository serviceAccounts;
  private final UserRepository users;
  private final ExternalAccessTokenService selfService;
  private final AuditEventRecorder audit;
  private final Clock clock;

  public ExternalAccessTokenAdminService(
      ExternalAccessTokenRepository tokens,
      ServiceAccountTokenRepository serviceAccountTokens,
      ServiceAccountRepository serviceAccounts,
      UserRepository users,
      ExternalAccessTokenService selfService,
      AuditEventRecorder audit,
      Clock clock) {
    this.tokens = tokens;
    this.serviceAccountTokens = serviceAccountTokens;
    this.serviceAccounts = serviceAccounts;
    this.users = users;
    this.selfService = selfService;
    this.audit = audit;
    this.clock = clock;
  }

  /**
   * The Bestandsliste, soonest expiry first, optionally narrowed by state or by "is still valid and
   * runs out within N days". Both filters are database predicates, not a pass over every row of the
   * installation, and the result is capped at {@link #MAX_ROWS} - the endpoint has no paging, so a
   * deployment that outgrew the cap is a visible, bounded gap rather than a growing query.
   */
  @Transactional(readOnly = true)
  public List<ExternalAccessTokenAdminView> list(
      UUID organizationId, ExternalAccessTokenStatus status, Integer expiringWithinDays) {
    Instant now = clock.instant();
    Pageable page = PageRequest.of(0, MAX_ROWS);
    List<ExternalAccessToken> rows;
    if (expiringWithinDays != null) {
      rows =
          tokens.findExpiringWithinForAdminList(
              organizationId, now, now.plus(Duration.ofDays(expiringWithinDays)), page);
      if (status != null) {
        rows = rows.stream().filter(token -> token.status(now) == status).toList();
      }
    } else {
      rows = byStatus(organizationId, status, now, page);
    }
    Map<UUID, String> libraryNames = selfService.namesOf(selfService.allSelected(rows));
    Map<UUID, User> owners =
        users
            .findAllById(rows.stream().map(ExternalAccessToken::getUserId).distinct().toList())
            .stream()
            .collect(Collectors.toMap(User::getId, Function.identity()));
    List<ExternalAccessTokenAdminView> views = new ArrayList<>();
    for (ExternalAccessToken token : rows) {
      views.add(
          new ExternalAccessTokenAdminView(
              token,
              ownerLabel(owners.get(token.getUserId())),
              new ExternalAccessTokenService.ExternalAccessTokenView(token, libraryNames)
                  .libraries()));
    }
    views.addAll(serviceAccountRows(organizationId, status, expiringWithinDays, now, page));
    // Both sources are capped and sorted alike; the merged list keeps the order and the cap.
    return views.stream()
        .sorted(
            Comparator.comparing((ExternalAccessTokenAdminView view) -> view.token().getExpiresAt())
                .thenComparing(view -> view.token().getId()))
        .limit(MAX_ROWS)
        .toList();
  }

  /**
   * The tokens of service accounts for the same list, owned by their account and without a library
   * selection, which they do not have (ADR-0035, Entscheidung 7).
   */
  private List<ExternalAccessTokenAdminView> serviceAccountRows(
      UUID organizationId,
      ExternalAccessTokenStatus status,
      Integer expiringWithinDays,
      Instant now,
      Pageable page) {
    List<ServiceAccountToken> rows;
    if (expiringWithinDays != null) {
      rows =
          serviceAccountTokens.findExpiringWithinForAdminList(
              organizationId, now, now.plus(Duration.ofDays(expiringWithinDays)), page);
      if (status != null) {
        rows = rows.stream().filter(token -> token.status(now) == status).toList();
      }
    } else {
      rows = serviceAccountRowsByStatus(organizationId, status, now, page);
    }
    Map<UUID, ServiceAccount> accounts =
        serviceAccounts
            .findAllById(
                rows.stream().map(ServiceAccountToken::getServiceAccountId).distinct().toList())
            .stream()
            .collect(Collectors.toMap(ServiceAccount::getId, Function.identity()));
    return rows.stream()
        .map(
            token -> {
              ServiceAccount account = accounts.get(token.getServiceAccountId());
              return new ExternalAccessTokenAdminView(
                  token,
                  token.getServiceAccountId(),
                  true,
                  account == null ? "" : account.getName(),
                  List.of());
            })
        .toList();
  }

  private List<ServiceAccountToken> serviceAccountRowsByStatus(
      UUID organizationId, ExternalAccessTokenStatus status, Instant now, Pageable page) {
    if (status == null) {
      return serviceAccountTokens.findForAdminList(organizationId, page);
    }
    return switch (status) {
      case ACTIVE -> serviceAccountTokens.findActiveForAdminList(organizationId, now, page);
      case EXPIRED -> serviceAccountTokens.findExpiredForAdminList(organizationId, now, page);
      case REVOKED ->
          serviceAccountTokens.findByRevocationReasonsForAdminList(
              organizationId, List.of(ExternalAccessTokenRevocationReason.OWNER), page);
      case BLOCKED ->
          serviceAccountTokens.findByRevocationReasonsForAdminList(
              organizationId,
              List.of(
                  ExternalAccessTokenRevocationReason.ADMIN,
                  ExternalAccessTokenRevocationReason.ACCOUNT_LIFECYCLE),
              page);
    };
  }

  private List<ExternalAccessToken> byStatus(
      UUID organizationId, ExternalAccessTokenStatus status, Instant now, Pageable page) {
    if (status == null) {
      return tokens.findForAdminList(organizationId, page);
    }
    return switch (status) {
      case ACTIVE -> tokens.findActiveForAdminList(organizationId, now, page);
      case EXPIRED -> tokens.findExpiredForAdminList(organizationId, now, page);
      case REVOKED ->
          tokens.findByRevocationReasonsForAdminList(
              organizationId, List.of(ExternalAccessTokenRevocationReason.OWNER), page);
      case BLOCKED ->
          tokens.findByRevocationReasonsForAdminList(
              organizationId,
              List.of(
                  ExternalAccessTokenRevocationReason.ADMIN,
                  ExternalAccessTokenRevocationReason.ACCOUNT_LIFECYCLE),
              page);
    };
  }

  /**
   * Blocks one token; already dead tokens keep the reason that ended them. A token of a person of
   * another organization is unknown here.
   */
  @Transactional
  public void block(UUID tokenId, UUID actorUserId, UUID organizationId) {
    ExternalAccessToken token =
        tokens
            .findById(tokenId)
            .filter(found -> inOrganization(found.getUserId(), organizationId))
            .orElse(null);
    if (token == null) {
      blockServiceAccountToken(tokenId, actorUserId, organizationId);
      return;
    }
    if (token.getRevokedAt() != null) {
      return;
    }
    token.revoke(ExternalAccessTokenRevocationReason.ADMIN, clock.instant());
    tokens.save(token);
    recordBlock(token, actorUserId, organizationId);
  }

  /**
   * Blocks every still-active token of one person of the organization; returns how many that was.
   */
  @Transactional
  public int blockAllOf(UUID ownerUserId, UUID actorUserId, UUID organizationId) {
    User owner =
        users
            .findById(ownerUserId)
            .filter(found -> organizationId.equals(found.getOrganizationId()))
            .orElse(null);
    if (owner == null) {
      return blockAllOfServiceAccount(ownerUserId, actorUserId, organizationId);
    }
    Instant now = clock.instant();
    List<ExternalAccessToken> active = tokens.findByUserIdAndRevokedAtIsNull(owner.getId());
    for (ExternalAccessToken token : active) {
      token.revoke(ExternalAccessTokenRevocationReason.ADMIN, now);
      tokens.save(token);
      recordBlock(token, actorUserId, organizationId);
    }
    return active.size();
  }

  /** The same lever for a token of a service account of the organization. */
  private void blockServiceAccountToken(UUID tokenId, UUID actorUserId, UUID organizationId) {
    ServiceAccountToken token =
        serviceAccountTokens
            .findById(tokenId)
            .filter(
                found ->
                    serviceAccounts
                        .findByIdAndOrganizationId(found.getServiceAccountId(), organizationId)
                        .isPresent())
            .orElseThrow(() -> new NotFoundException("Zugangstoken nicht gefunden"));
    if (token.revoke(ExternalAccessTokenRevocationReason.ADMIN, clock.instant())) {
      serviceAccountTokens.save(token);
      recordServiceAccountBlock(token, actorUserId, organizationId);
    }
  }

  /** Every live token of one service account - the owner id of its rows in the list. */
  private int blockAllOfServiceAccount(
      UUID serviceAccountId, UUID actorUserId, UUID organizationId) {
    ServiceAccount account =
        serviceAccounts
            .findByIdAndOrganizationId(serviceAccountId, organizationId)
            .orElseThrow(() -> new NotFoundException("Konto nicht gefunden"));
    Instant now = clock.instant();
    int blocked = 0;
    for (ServiceAccountToken token :
        serviceAccountTokens.findByServiceAccountIdAndRevokedAtIsNull(account.getId())) {
      if (token.revoke(ExternalAccessTokenRevocationReason.ADMIN, now)) {
        serviceAccountTokens.save(token);
        recordServiceAccountBlock(token, actorUserId, organizationId);
        blocked++;
      }
    }
    return blocked;
  }

  private void recordServiceAccountBlock(
      ServiceAccountToken token, UUID actorUserId, UUID organizationId) {
    audit.recordUserAction(
        AuditEvent.builder()
            .organizationId(organizationId)
            .actor(actorUserId)
            .type(AuditEventType.API_TOKEN_REVOKED)
            .object(AuditObjectType.API_TOKEN, token.getId(), null)
            .after(
                Map.of(
                    "reason",
                    ExternalAccessTokenRevocationReason.ADMIN.name(),
                    "by",
                    "ADMIN",
                    "serviceAccountId",
                    token.getServiceAccountId().toString()))
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }

  private boolean inOrganization(UUID userId, UUID organizationId) {
    return users
        .findById(userId)
        .map(user -> organizationId.equals(user.getOrganizationId()))
        .orElse(false);
  }

  private void recordBlock(ExternalAccessToken token, UUID actorUserId, UUID organizationId) {
    audit.recordUserAction(
        AuditEvent.builder()
            .organizationId(organizationId)
            .actor(actorUserId)
            .type(AuditEventType.API_TOKEN_REVOKED)
            .object(AuditObjectType.API_TOKEN, token.getId(), null)
            .after(
                Map.of("reason", ExternalAccessTokenRevocationReason.ADMIN.name(), "by", "ADMIN"))
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }

  private static String ownerLabel(User owner) {
    if (owner == null) {
      return "";
    }
    return owner.getDisplayName() == null ? "" : owner.getDisplayName();
  }

  /**
   * One row of the administration's list - without a usage date, by design. {@code ownerId} is the
   * person, or with {@code serviceAccount} the service account; the latter has no selection.
   */
  public record ExternalAccessTokenAdminView(
      ListedAccessToken token,
      UUID ownerId,
      boolean serviceAccount,
      String ownerDisplayName,
      List<SelectedLibrary> libraries) {

    public ExternalAccessTokenAdminView(
        ExternalAccessToken token, String ownerDisplayName, List<SelectedLibrary> libraries) {
      this(token, token.getUserId(), false, ownerDisplayName, libraries);
    }
  }
}
