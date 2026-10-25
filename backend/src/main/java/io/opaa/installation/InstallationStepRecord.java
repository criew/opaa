package io.opaa.installation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One saved step of a pending installation (ADR-0060, Entscheidung 6). {@code payload} is the JSON
 * of the step's values without secrets; {@code secret} is the password hash of the first account or
 * the encrypted model key. Saving new values always discards the outcome of an earlier check.
 */
@Entity
@Table(name = "installation_steps")
public class InstallationStepRecord {

  /** Upper bound of a stored check message; longer provider texts are cut. */
  static final int MAX_CHECK_MESSAGE = 1000;

  @Id
  @Enumerated(EnumType.STRING)
  @Column(length = 30)
  private InstallationStep step;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private String payload;

  @Column private String secret;

  @Column(name = "check_succeeded")
  private Boolean checkSucceeded;

  @Column(name = "check_message", length = MAX_CHECK_MESSAGE)
  private String checkMessage;

  @Column(name = "checked_at")
  private Instant checkedAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected InstallationStepRecord() {}

  InstallationStepRecord(InstallationStep step) {
    this.step = step;
  }

  InstallationStep getStep() {
    return step;
  }

  String getPayload() {
    return payload;
  }

  String getSecret() {
    return secret;
  }

  String getCheckMessage() {
    return checkMessage;
  }

  boolean isCheckSucceeded() {
    return Boolean.TRUE.equals(checkSucceeded);
  }

  void replaceValues(String payload, String secret, Instant now) {
    this.payload = payload;
    this.secret = secret;
    this.checkSucceeded = null;
    this.checkMessage = null;
    this.checkedAt = null;
    this.updatedAt = now;
  }

  /** Replaces the values with what a successful check learned, keeping the secret. */
  void recordCheckedValues(String payload) {
    this.payload = payload;
  }

  void recordCheck(boolean succeeded, String message, Instant now) {
    this.checkSucceeded = succeeded;
    this.checkMessage =
        message == null || message.length() <= MAX_CHECK_MESSAGE
            ? message
            : message.substring(0, MAX_CHECK_MESSAGE);
    this.checkedAt = now;
    this.updatedAt = now;
  }
}
