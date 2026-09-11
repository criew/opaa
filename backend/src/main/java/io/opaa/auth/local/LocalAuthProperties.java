package io.opaa.auth.local;

import io.opaa.security.ValidSecret;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code opaa.auth.local.*} (ADR-0033, Entscheidungen 6 and 7): the root secret every local key is
 * derived from and the session limits of the local issuer. Defaults are the ADR's; the compact
 * constructor refuses limits above the ADR's ceilings and orderings that make no sense (an idle
 * limit longer than the absolute one, administrator limits longer than the regular ones, an access
 * token that outlives the shortest refresh window) in every profile, naming the environment
 * variable. The secret's strength is checked by {@link LocalAuthSecretGuard} in the {@code oidc}
 * profile only. The issuer is not configurable: {@link #ISSUER} is installation-independent so an
 * account's identity survives a move (Entscheidung 2).
 *
 * @param jwtSecret {@code OPAA_AUTH_JWT_SECRET}, HKDF input keying material; trimmed, never null
 * @param accessTokenTtl lifetime of a local access token (default 15 minutes)
 * @param refreshTokenTtl idle limit of a session - the refresh token's lifetime (default 7 days, at
 *     most 30)
 * @param sessionMaxLifetime absolute limit of a refresh-token family that no rotation extends
 *     (default 30 days, at most 90)
 * @param adminRefreshTokenTtl idle limit for local {@code SYSTEM_ADMIN} accounts (default 4 hours)
 * @param adminSessionMaxLifetime absolute limit for local {@code SYSTEM_ADMIN} accounts (default 12
 *     hours)
 * @param cookieSecure whether the refresh cookie carries {@code Secure} (default true; {@code
 *     false} only for local HTTP)
 */
@ConfigurationProperties(prefix = "opaa.auth.local")
public record LocalAuthProperties(
    @ValidSecret String jwtSecret,
    Duration accessTokenTtl,
    Duration refreshTokenTtl,
    Duration sessionMaxLifetime,
    Duration adminRefreshTokenTtl,
    Duration adminSessionMaxLifetime,
    Boolean cookieSecure) {

  public static final String ISSUER = "urn:opaa:local";
  public static final String JWT_SECRET_VARIABLE = "OPAA_AUTH_JWT_SECRET";

  public static final Duration MAX_REFRESH_TOKEN_TTL = Duration.ofDays(30);
  public static final Duration MAX_SESSION_MAX_LIFETIME = Duration.ofDays(90);

  private static final String PREFIX = "opaa.auth.local.";

  public LocalAuthProperties {
    jwtSecret = jwtSecret == null ? "" : jwtSecret.trim();
    accessTokenTtl = accessTokenTtl != null ? accessTokenTtl : Duration.ofMinutes(15);
    refreshTokenTtl = refreshTokenTtl != null ? refreshTokenTtl : Duration.ofDays(7);
    sessionMaxLifetime = sessionMaxLifetime != null ? sessionMaxLifetime : Duration.ofDays(30);
    adminRefreshTokenTtl =
        adminRefreshTokenTtl != null ? adminRefreshTokenTtl : Duration.ofHours(4);
    adminSessionMaxLifetime =
        adminSessionMaxLifetime != null ? adminSessionMaxLifetime : Duration.ofHours(12);
    cookieSecure = cookieSecure == null ? Boolean.TRUE : cookieSecure;

    requirePositive(accessTokenTtl, Setting.ACCESS_TOKEN_TTL);
    requirePositive(refreshTokenTtl, Setting.REFRESH_TOKEN_TTL);
    requirePositive(sessionMaxLifetime, Setting.SESSION_MAX_LIFETIME);
    requirePositive(adminRefreshTokenTtl, Setting.ADMIN_REFRESH_TOKEN_TTL);
    requirePositive(adminSessionMaxLifetime, Setting.ADMIN_SESSION_MAX_LIFETIME);
    requireAtMost(refreshTokenTtl, MAX_REFRESH_TOKEN_TTL, Setting.REFRESH_TOKEN_TTL, "30 days");
    requireAtMost(
        sessionMaxLifetime, MAX_SESSION_MAX_LIFETIME, Setting.SESSION_MAX_LIFETIME, "90 days");
    requireOrdered(
        refreshTokenTtl,
        Setting.REFRESH_TOKEN_TTL,
        sessionMaxLifetime,
        Setting.SESSION_MAX_LIFETIME);
    requireOrdered(
        adminRefreshTokenTtl,
        Setting.ADMIN_REFRESH_TOKEN_TTL,
        adminSessionMaxLifetime,
        Setting.ADMIN_SESSION_MAX_LIFETIME);
    requireOrdered(
        adminRefreshTokenTtl,
        Setting.ADMIN_REFRESH_TOKEN_TTL,
        refreshTokenTtl,
        Setting.REFRESH_TOKEN_TTL);
    requireOrdered(
        adminSessionMaxLifetime,
        Setting.ADMIN_SESSION_MAX_LIFETIME,
        sessionMaxLifetime,
        Setting.SESSION_MAX_LIFETIME);
    requireOrdered(
        accessTokenTtl,
        Setting.ACCESS_TOKEN_TTL,
        adminRefreshTokenTtl,
        Setting.ADMIN_REFRESH_TOKEN_TTL);
  }

  /**
   * Whether any secret is configured at all - its strength is {@link LocalAuthSecretGuard}'s job.
   */
  public boolean hasJwtSecret() {
    return !jwtSecret.isEmpty();
  }

  private static void requirePositive(Duration value, Setting setting) {
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(setting.describe() + " must be positive, got " + value);
    }
  }

  private static void requireAtMost(
      Duration value, Duration ceiling, Setting setting, String limit) {
    if (value.compareTo(ceiling) > 0) {
      throw new IllegalArgumentException(
          setting.describe() + " must not exceed " + limit + " (ADR-0033), got " + value);
    }
  }

  private static void requireOrdered(
      Duration shorter, Setting shorterSetting, Duration longer, Setting longerSetting) {
    if (shorter.compareTo(longer) > 0) {
      throw new IllegalArgumentException(
          shorterSetting.describe()
              + " ("
              + shorter
              + ") must not exceed "
              + longerSetting.describe()
              + " ("
              + longer
              + ")");
    }
  }

  private enum Setting {
    ACCESS_TOKEN_TTL("access-token-ttl", "OPAA_AUTH_LOCAL_ACCESS_TOKEN_TTL"),
    REFRESH_TOKEN_TTL("refresh-token-ttl", "OPAA_AUTH_LOCAL_REFRESH_TOKEN_TTL"),
    SESSION_MAX_LIFETIME("session-max-lifetime", "OPAA_AUTH_LOCAL_SESSION_MAX_LIFETIME"),
    ADMIN_REFRESH_TOKEN_TTL("admin-refresh-token-ttl", "OPAA_AUTH_LOCAL_ADMIN_REFRESH_TOKEN_TTL"),
    ADMIN_SESSION_MAX_LIFETIME(
        "admin-session-max-lifetime", "OPAA_AUTH_LOCAL_ADMIN_SESSION_MAX_LIFETIME");

    private final String property;
    private final String variable;

    Setting(String property, String variable) {
      this.property = property;
      this.variable = variable;
    }

    String describe() {
      return PREFIX + property + " (" + variable + ")";
    }
  }
}
