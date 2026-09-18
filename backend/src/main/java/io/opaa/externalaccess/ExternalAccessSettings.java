package io.opaa.externalaccess;

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
 * The single settings row of the external access channel (#1717, ADR-0035; pattern {@code
 * LocalAuthSettings}). {@code enabled} is the installation-wide switch and at the same time the
 * emergency stop; it is stored, never derived. The bounds of every value are enforced here and by
 * the table's CHECKs, so no write path can weaken them.
 */
@Entity
@Table(name = "external_access_settings")
public class ExternalAccessSettings {

  public static final int SINGLETON_ID = 1;

  /**
   * The channel settings as one immutable unit, normalised and range-checked on construction - what
   * the API reads and replaces wholesale.
   */
  public record Values(
      boolean enabled,
      int tokenMaxLifetimeDays,
      int tokenRateLimitPerHour,
      List<String> allowedCidrs,
      int massRetrievalAlertThreshold,
      String serverInstructions) {

    public Values {
      allowedCidrs = CidrList.normalize(allowedCidrs);
      serverInstructions = serverInstructions == null ? "" : serverInstructions.strip();
      requireBetween(
          tokenMaxLifetimeDays,
          ExternalAccessDefaults.MIN_TOKEN_MAX_LIFETIME_DAYS,
          ExternalAccessDefaults.MAX_TOKEN_MAX_LIFETIME_DAYS,
          "tokenMaxLifetimeDays");
      requireBetween(
          tokenRateLimitPerHour,
          ExternalAccessDefaults.MIN_TOKEN_RATE_LIMIT_PER_HOUR,
          ExternalAccessDefaults.MAX_TOKEN_RATE_LIMIT_PER_HOUR,
          "tokenRateLimitPerHour");
      requireBetween(
          massRetrievalAlertThreshold,
          ExternalAccessDefaults.MIN_MASS_RETRIEVAL_ALERT_THRESHOLD,
          ExternalAccessDefaults.MAX_MASS_RETRIEVAL_ALERT_THRESHOLD,
          "massRetrievalAlertThreshold");
    }

    /** The delivered settings - what the seed row carries. */
    public static Values defaults() {
      return new Values(
          ExternalAccessDefaults.ENABLED,
          ExternalAccessDefaults.TOKEN_MAX_LIFETIME_DAYS,
          ExternalAccessDefaults.TOKEN_RATE_LIMIT_PER_HOUR,
          ExternalAccessDefaults.ALLOWED_CIDRS,
          ExternalAccessDefaults.MASS_RETRIEVAL_ALERT_THRESHOLD,
          ExternalAccessDefaults.SERVER_INSTRUCTIONS);
    }

    private static void requireBetween(int value, int min, int max, String name) {
      if (value < min || value > max) {
        throw new IllegalArgumentException(
            name + " must be between " + min + " and " + max + ", got " + value);
      }
    }
  }

  @Id private Integer id;

  @Column(name = "enabled", nullable = false)
  private boolean enabled;

  @Column(name = "token_max_lifetime_days", nullable = false)
  private int tokenMaxLifetimeDays;

  @Column(name = "token_rate_limit_per_hour", nullable = false)
  private int tokenRateLimitPerHour;

  @Convert(converter = CidrListConverter.class)
  @Column(name = "allowed_cidrs", nullable = false, columnDefinition = "text")
  private List<String> allowedCidrs = List.of();

  @Column(name = "mass_retrieval_alert_threshold", nullable = false)
  private int massRetrievalAlertThreshold;

  @Column(name = "server_instructions", nullable = false)
  private String serverInstructions = "";

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "updated_by")
  private UUID updatedBy;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  protected ExternalAccessSettings() {}

  public Values values() {
    return new Values(
        enabled,
        tokenMaxLifetimeDays,
        tokenRateLimitPerHour,
        allowedCidrs,
        massRetrievalAlertThreshold,
        serverInstructions);
  }

  /** Replaces every channel value at once; {@code updatedBy} may be null for a system change. */
  public void replace(Values values, UUID updatedBy, Instant now) {
    Objects.requireNonNull(values, "values");
    this.enabled = values.enabled();
    this.tokenMaxLifetimeDays = values.tokenMaxLifetimeDays();
    this.tokenRateLimitPerHour = values.tokenRateLimitPerHour();
    this.allowedCidrs = values.allowedCidrs();
    this.massRetrievalAlertThreshold = values.massRetrievalAlertThreshold();
    this.serverInstructions = values.serverInstructions();
    this.updatedBy = updatedBy;
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  public Integer getId() {
    return id;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public int getTokenMaxLifetimeDays() {
    return tokenMaxLifetimeDays;
  }

  public int getTokenRateLimitPerHour() {
    return tokenRateLimitPerHour;
  }

  public List<String> getAllowedCidrs() {
    return allowedCidrs;
  }

  public int getMassRetrievalAlertThreshold() {
    return massRetrievalAlertThreshold;
  }

  public String getServerInstructions() {
    return serverInstructions;
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
