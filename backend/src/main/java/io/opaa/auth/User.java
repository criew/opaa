package io.opaa.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
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

  @Column(name = "last_login_at")
  private Instant lastLoginAt;

  protected User() {}

  public User(String subject, String issuer, String email, String displayName) {
    this.id = UUID.randomUUID();
    this.subject = subject;
    this.issuer = issuer;
    this.email = email;
    this.displayName = displayName;
    this.createdAt = Instant.now();
    this.lastLoginAt = Instant.now();
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
}
