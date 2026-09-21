package io.opaa.auth;

import io.opaa.api.types.SystemRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
    name = "users",
    uniqueConstraints =
        @UniqueConstraint(
            name = "users_subject_issuer_unique",
            columnNames = {"subject", "issuer"}))
public class User {

  @Id private UUID id;

  @Column(name = "subject", nullable = false, length = 255)
  private String subject;

  @Column(name = "issuer", nullable = false, length = 500)
  private String issuer;

  @Column(name = "email", length = 320)
  private String email;

  @Column(name = "display_name", length = 255)
  private String displayName;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "system_role", nullable = false, length = 20)
  private SystemRole systemRole = SystemRole.USER;

  @Column(name = "last_login_at")
  private Instant lastLoginAt;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  /**
   * When the directory synchronisation locked this account, {@code null} while it is not locked
   * (#1818, ADR-0036 Entscheidung 3). The one stored fact the directory contributes about an
   * account; everything else about its state stays derived (ADR-0033, Entscheidung 3).
   */
  @Column(name = "directory_locked_at")
  private Instant directoryLockedAt;

  protected User() {}

  /**
   * {@code createdAt}/{@code lastLoginAt} come from the wall clock ({@link Instant#now()}), not
   * {@link UserService}'s injected {@code Clock} - a test constructing a {@link User} with a fixed
   * clock cannot control these two timestamps this way.
   */
  public User(String subject, String issuer, String email, String displayName) {
    this.id = UUID.randomUUID();
    this.subject = subject;
    this.issuer = issuer;
    this.email = email;
    this.displayName = displayName;
    this.systemRole = SystemRole.USER;
    this.createdAt = Instant.now();
    this.lastLoginAt = Instant.now();
  }

  /**
   * A local account (ADR-0033, Entscheidung 2): issuer {@link LocalIssuer#URN} and the account's
   * own id as its subject, so a changed address never changes the identity.
   */
  public static User localAccount(String email, String displayName) {
    User user = new User(null, LocalIssuer.URN, email, displayName);
    user.subject = user.id.toString();
    return user;
  }

  /**
   * Hands the account over to a provider identity (ADR-0033, Entscheidung 12): the one act that
   * ever changes {@code (issuer, subject)} of an existing row. Everything keyed by {@code id} -
   * spaces, memberships, the system role and the pseudonym of the audit trail - stays untouched,
   * which is the whole point of the handover.
   */
  public void handOverTo(String issuer, String subject) {
    this.issuer = Objects.requireNonNull(issuer, "issuer");
    this.subject = Objects.requireNonNull(subject, "subject");
  }

  public UUID getId() {
    return id;
  }

  public String getSubject() {
    return subject;
  }

  public String getIssuer() {
    return issuer;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getLastLoginAt() {
    return lastLoginAt;
  }

  public void setLastLoginAt(Instant lastLoginAt) {
    this.lastLoginAt = lastLoginAt;
  }

  public SystemRole getSystemRole() {
    return systemRole;
  }

  public void setSystemRole(SystemRole systemRole) {
    this.systemRole = systemRole;
  }

  /**
   * Takes the access away because the directory reports the account as disabled or no longer
   * reports it at all (#1818). Reversible: memberships, spaces and the system role stay untouched,
   * and {@link #unlockFromDirectory()} restores the account unchanged.
   */
  public void lockFromDirectory(Instant lockedAt) {
    this.directoryLockedAt = Objects.requireNonNull(lockedAt, "lockedAt");
  }

  /** Gives the access back because the directory reports the account as enabled again (#1818). */
  public void unlockFromDirectory() {
    this.directoryLockedAt = null;
  }

  public boolean isDirectoryLocked() {
    return directoryLockedAt != null;
  }

  public Instant getDirectoryLockedAt() {
    return directoryLockedAt;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public void setOrganizationId(UUID organizationId) {
    this.organizationId = organizationId;
  }
}
