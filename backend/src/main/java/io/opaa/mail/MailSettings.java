package io.opaa.mail;

import io.opaa.api.types.MailEncryption;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The single, system-wide SMTP configuration (#1536, ADR-0033 Entscheidung 10) - a singleton for
 * the same reason {@link io.opaa.branding.BrandingSettings} is one: OPAA is deployed once per
 * Behörde, and "der Mailversand" is what that one deployment does.
 *
 * <p><b>The password is only ever held here as ciphertext</b> ({@code enc:v1:…}, written by {@code
 * io.opaa.security.SettingsEncryptor}); no code path stores or returns the clear value, and the API
 * answers with the mask {@code ***}.
 *
 * <p>The three status fields are the durable record of whether mail actually works. They are
 * written by {@link MailService} after every attempt and shown on the settings page - a {@code
 * Failed} that only reaches the log of a container nobody reads is not visible at all.
 */
@Entity
@Table(name = "mail_settings")
public class MailSettings {

  /** Always {@code 1} - see the class Javadoc; enforced by {@code chk_mail_settings_singleton}. */
  public static final int SINGLETON_ID = 1;

  /** Maximum stored length of {@link #lastFailureReason}; longer causes are truncated. */
  static final int MAX_FAILURE_REASON_LENGTH = 500;

  @Id private Integer id;

  @Column(name = "enabled", nullable = false)
  private boolean enabled;

  @Column(name = "host", length = 255)
  private String host;

  @Column(name = "port")
  private Integer port;

  @Column(name = "username", length = 255)
  private String username;

  @Column(name = "password_ciphertext")
  private String passwordCiphertext;

  @Enumerated(EnumType.STRING)
  @Column(name = "encryption", length = 20, nullable = false)
  private MailEncryption encryption = MailEncryption.STARTTLS;

  @Column(name = "from_address", length = 320)
  private String fromAddress;

  @Column(name = "from_name", length = 120)
  private String fromName;

  @Column(name = "last_success_at")
  private Instant lastSuccessAt;

  @Column(name = "last_failure_at")
  private Instant lastFailureAt;

  @Column(name = "last_failure_reason", length = MAX_FAILURE_REASON_LENGTH)
  private String lastFailureReason;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "updated_by")
  private UUID updatedBy;

  protected MailSettings() {}

  public Integer getId() {
    return id;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public String getHost() {
    return host;
  }

  public Integer getPort() {
    return port;
  }

  public String getUsername() {
    return username;
  }

  public String getPasswordCiphertext() {
    return passwordCiphertext;
  }

  public MailEncryption getEncryption() {
    return encryption;
  }

  public String getFromAddress() {
    return fromAddress;
  }

  public String getFromName() {
    return fromName;
  }

  public Instant getLastSuccessAt() {
    return lastSuccessAt;
  }

  public Instant getLastFailureAt() {
    return lastFailureAt;
  }

  public String getLastFailureReason() {
    return lastFailureReason;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public UUID getUpdatedBy() {
    return updatedBy;
  }

  /**
   * Replaces every administrable field at once - {@code PUT}'s own semantics. One method rather
   * than nine setters: there is no code path that legitimately changes the host without deciding
   * about the port and the encryption, and separate setters would invite one.
   *
   * <p>{@code passwordCiphertext} is passed already encrypted; this class never sees a clear
   * password.
   */
  void replaceSettings(
      boolean enabled,
      String host,
      Integer port,
      String username,
      String passwordCiphertext,
      MailEncryption encryption,
      String fromAddress,
      String fromName,
      UUID actorUserId) {
    this.enabled = enabled;
    this.host = host;
    this.port = port;
    this.username = username;
    this.passwordCiphertext = passwordCiphertext;
    this.encryption = encryption;
    this.fromAddress = fromAddress;
    this.fromName = fromName;
    this.updatedBy = actorUserId;
    this.updatedAt = Instant.now();
  }

  /**
   * Records a successful send. The failure fields are deliberately kept: "zuletzt erfolgreich" and
   * "zuletzt fehlgeschlagen" answer different questions, and clearing the older one would hide that
   * mail is failing intermittently.
   */
  void recordSuccess(Instant at) {
    this.lastSuccessAt = at;
  }

  /** Records a failed send with its cause, truncated to what the column holds. */
  void recordFailure(Instant at, String reason) {
    this.lastFailureAt = at;
    this.lastFailureReason = truncate(reason);
  }

  private static String truncate(String reason) {
    if (reason == null) {
      return null;
    }
    return reason.length() <= MAX_FAILURE_REASON_LENGTH
        ? reason
        : reason.substring(0, MAX_FAILURE_REASON_LENGTH);
  }
}
