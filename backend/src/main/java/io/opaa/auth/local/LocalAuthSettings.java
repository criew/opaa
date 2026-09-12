package io.opaa.auth.local;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The single settings row of the local account management (ADR-0033, Entscheidung 3; pattern {@link
 * io.opaa.branding.BrandingSettings}). The main switch is not here but the {@code enabled} flag of
 * the {@code LOCAL} provider row. Unlike branding, every value is a policy with the ADR's default
 * already stored, and the bounds the ADR names are enforced twice: by {@link Values} and by the
 * table's CHECKs. Self-registration works only with a non-empty domain list - an empty list means
 * "nobody can register", never "everybody".
 */
@Entity
@Table(name = "local_auth_settings")
public class LocalAuthSettings {

  public static final int SINGLETON_ID = 1;

  public static final int MIN_PASSWORD_MIN_LENGTH = 8;
  public static final int MAX_PASSWORD_MIN_LENGTH = 64;
  public static final int MIN_INVITATION_TOKEN_TTL_HOURS = 1;
  public static final int MAX_INVITATION_TOKEN_TTL_HOURS = 720;
  public static final int MIN_RESET_TOKEN_TTL_MINUTES = 1;
  public static final int MAX_RESET_TOKEN_TTL_MINUTES = 1440;
  public static final int MIN_DEFAULT_EXPIRY_DAYS = 1;
  public static final int MIN_INACTIVE_DAYS = 30;

  /**
   * The eight policy values as one immutable unit, normalized (domains trimmed, lowercased,
   * de-duplicated) and range-checked on construction - what the API reads and replaces wholesale.
   */
  public record Values(
      boolean selfRegistrationEnabled,
      List<String> selfRegistrationAllowedDomains,
      boolean passwordResetEnabled,
      int passwordMinLength,
      int invitationTokenTtlHours,
      int resetTokenTtlMinutes,
      int defaultExpiryDays,
      int inactiveDays) {

    public Values {
      selfRegistrationAllowedDomains =
          DomainListConverter.normalize(selfRegistrationAllowedDomains);
      requireBetween(
          passwordMinLength, MIN_PASSWORD_MIN_LENGTH, MAX_PASSWORD_MIN_LENGTH, "passwordMinLength");
      requireBetween(
          invitationTokenTtlHours,
          MIN_INVITATION_TOKEN_TTL_HOURS,
          MAX_INVITATION_TOKEN_TTL_HOURS,
          "invitationTokenTtlHours");
      requireBetween(
          resetTokenTtlMinutes,
          MIN_RESET_TOKEN_TTL_MINUTES,
          MAX_RESET_TOKEN_TTL_MINUTES,
          "resetTokenTtlMinutes");
      requireBetween(
          defaultExpiryDays, MIN_DEFAULT_EXPIRY_DAYS, Integer.MAX_VALUE, "defaultExpiryDays");
      requireBetween(inactiveDays, MIN_INACTIVE_DAYS, Integer.MAX_VALUE, "inactiveDays");
    }

    /** The ADR's defaults - what the seed row carries. */
    public static Values defaults() {
      return new Values(false, List.of(), true, 12, 72, 30, 90, 90);
    }

    private static void requireBetween(int value, int min, int max, String name) {
      if (value < min || value > max) {
        throw new IllegalArgumentException(
            name + " must be between " + min + " and " + max + " (ADR-0033), got " + value);
      }
    }
  }

  @Id private Integer id;

  @Column(name = "self_registration_enabled", nullable = false)
  private boolean selfRegistrationEnabled;

  @Convert(converter = DomainListConverter.class)
  @Column(name = "self_registration_allowed_domains", nullable = false, length = 2000)
  private List<String> selfRegistrationAllowedDomains = List.of();

  @Column(name = "password_reset_enabled", nullable = false)
  private boolean passwordResetEnabled;

  @Column(name = "password_min_length", nullable = false)
  private int passwordMinLength;

  @Column(name = "invitation_token_ttl_hours", nullable = false)
  private int invitationTokenTtlHours;

  @Column(name = "reset_token_ttl_minutes", nullable = false)
  private int resetTokenTtlMinutes;

  @Column(name = "default_expiry_days", nullable = false)
  private int defaultExpiryDays;

  @Column(name = "inactive_days", nullable = false)
  private int inactiveDays;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "updated_by")
  private UUID updatedBy;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  protected LocalAuthSettings() {}

  public Values values() {
    return new Values(
        selfRegistrationEnabled,
        selfRegistrationAllowedDomains,
        passwordResetEnabled,
        passwordMinLength,
        invitationTokenTtlHours,
        resetTokenTtlMinutes,
        defaultExpiryDays,
        inactiveDays);
  }

  /** Replaces every policy value at once; {@code updatedBy} may be null for a system change. */
  public void replace(Values values, UUID updatedBy, Instant now) {
    Objects.requireNonNull(values, "values");
    this.selfRegistrationEnabled = values.selfRegistrationEnabled();
    this.selfRegistrationAllowedDomains = values.selfRegistrationAllowedDomains();
    this.passwordResetEnabled = values.passwordResetEnabled();
    this.passwordMinLength = values.passwordMinLength();
    this.invitationTokenTtlHours = values.invitationTokenTtlHours();
    this.resetTokenTtlMinutes = values.resetTokenTtlMinutes();
    this.defaultExpiryDays = values.defaultExpiryDays();
    this.inactiveDays = values.inactiveDays();
    this.updatedBy = updatedBy;
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  public Integer getId() {
    return id;
  }

  public boolean isSelfRegistrationEnabled() {
    return selfRegistrationEnabled;
  }

  public List<String> getSelfRegistrationAllowedDomains() {
    return selfRegistrationAllowedDomains;
  }

  public boolean isPasswordResetEnabled() {
    return passwordResetEnabled;
  }

  public int getPasswordMinLength() {
    return passwordMinLength;
  }

  public int getInvitationTokenTtlHours() {
    return invitationTokenTtlHours;
  }

  public int getResetTokenTtlMinutes() {
    return resetTokenTtlMinutes;
  }

  public int getDefaultExpiryDays() {
    return defaultExpiryDays;
  }

  public int getInactiveDays() {
    return inactiveDays;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public UUID getUpdatedBy() {
    return updatedBy;
  }

  public long getVersion() {
    return version;
  }
}
