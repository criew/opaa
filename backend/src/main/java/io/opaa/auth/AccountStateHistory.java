package io.opaa.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A half-open interval {@code [validFrom, validTo)} recording one period an account was {@link
 * AccountState#ACTIVE} or {@link AccountState#BLOCKED} (#1818, ADR-0036 Entscheidung 8). {@code
 * validTo == null} means the interval is open - and the open interval is at the same time the
 * account's current state, which is why there is no state column on {@code users}.
 *
 * <p><b>The chain begins with the first change, not with the account.</b> An account nobody ever
 * blocked has no row here at all; "was this account usable on day Y" is then answered by the
 * absence of a blocking interval together with {@code users.created_at}. Opening one row per
 * account up front would make every account permanently undeletable through {@code user_id ON
 * DELETE RESTRICT} - see changelog 060.
 *
 * <p>Written and closed exclusively by {@link AccountStateService}, which takes its boundaries from
 * the one {@code PermissionHistoryClock} every other rights-history writer uses, so successive
 * intervals of the same account have strictly increasing boundaries.
 */
@Entity
@Table(name = "account_state_history")
public class AccountStateHistory {

  @Id private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 20)
  private AccountState state;

  @Enumerated(EnumType.STRING)
  @Column(name = "cause", nullable = false, length = 30)
  private AccountStateHistoryCause cause;

  @Column(name = "provider_id")
  private UUID providerId;

  @Column(name = "actor_user_id")
  private UUID actorUserId;

  @Column(name = "valid_from", nullable = false)
  private Instant validFrom;

  @Column(name = "valid_to")
  private Instant validTo;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected AccountStateHistory() {}

  private AccountStateHistory(
      UUID userId,
      UUID organizationId,
      AccountStateHistoryCause cause,
      UUID providerId,
      UUID actorUserId,
      Instant validFrom) {
    this.id = UUID.randomUUID();
    this.userId = Objects.requireNonNull(userId, "userId");
    this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
    this.cause = Objects.requireNonNull(cause, "cause");
    this.state = cause.state();
    this.providerId = providerId;
    this.actorUserId = actorUserId;
    this.validFrom = Objects.requireNonNull(validFrom, "validFrom");
  }

  /**
   * Opens the interval the account is in from {@code validFrom} on. The state follows from the
   * cause ({@link AccountStateHistoryCause#state()}), never from a second argument that could
   * disagree with it.
   */
  public static AccountStateHistory open(
      UUID userId,
      UUID organizationId,
      AccountStateHistoryCause cause,
      UUID providerId,
      UUID actorUserId,
      Instant validFrom) {
    return new AccountStateHistory(
        userId, organizationId, cause, providerId, actorUserId, validFrom);
  }

  public void close(Instant validTo) {
    this.validTo = Objects.requireNonNull(validTo, "validTo");
  }

  @PrePersist
  void onPersist() {
    if (createdAt == null) {
      createdAt = Instant.now();
    }
  }

  public UUID getId() {
    return id;
  }

  public UUID getUserId() {
    return userId;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public AccountState getState() {
    return state;
  }

  public AccountStateHistoryCause getCause() {
    return cause;
  }

  public UUID getProviderId() {
    return providerId;
  }

  public UUID getActorUserId() {
    return actorUserId;
  }

  public Instant getValidFrom() {
    return validFrom;
  }

  public Instant getValidTo() {
    return validTo;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
