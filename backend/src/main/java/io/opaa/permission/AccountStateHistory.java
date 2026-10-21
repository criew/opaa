package io.opaa.permission;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A half-open interval {@code [validFrom, validTo)} recording one period an account was {@link
 * AccountState#ACTIVE} or {@link AccountState#LOCKED} (ADR-0036 Entscheidung 8). {@code validTo ==
 * null} means the account is in that state right now. A failed-login lockout leaves no interval: it
 * is short, ends by itself and is caused by whoever types the address.
 *
 * <p>Written and closed only by the database ({@code account_state_reconcile}, triggered by every
 * change of {@code users}, {@code local_credentials} and {@code oidc_providers} that can change
 * whether an account is usable); this entity is read-only. An account whose state never changed has
 * no row at all - see {@link AccountStateHistoryCause#ACCOUNT_CREATED}.
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

  @Column(name = "valid_from", nullable = false)
  private Instant validFrom;

  @Column(name = "valid_to")
  private Instant validTo;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected AccountStateHistory() {}

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
