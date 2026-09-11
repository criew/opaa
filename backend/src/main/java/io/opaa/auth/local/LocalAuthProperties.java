package io.opaa.auth.local;

import io.opaa.security.ValidSecret;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

/**
 * {@code opaa.auth.local.*} (ADR-0033, Entscheidungen 6 and 7): the root secret every local key is
 * derived from and the session limits of the local issuer. Defaults are the ADR's; the compact
 * constructor refuses limits above the ADR's ceilings and orderings that make no sense (an idle
 * limit longer than the absolute one, administrator limits longer than the regular ones, an access
 * token that outlives the shortest refresh window) in every profile, naming the environment
 * variable. The secret's strength is checked by {@link LocalAuthSecretGuard} in the {@code oidc}
 * profile only. The issuer is not configurable - {@link io.opaa.auth.LocalIssuer#URN} is
 * installation-independent so an account's identity survives a move (Entscheidung 2).
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
 * @param initialAdminPassword {@code OPAA_INITIAL_ADMIN_PASSWORD}: the bootstrap administrator's
 *     password for automated deployments (CI, E2E) - used as is, without a forced change; empty
 *     means "generate one and print it once" (ADR-0033, Entscheidung 5)
 * @param adminReset {@code OPAA_LOCAL_ADMIN_RESET}: {@code force} restores the bootstrap
 *     administrator once at start-up (Entscheidung 5); the operator removes it afterwards
 * @param adminAllowedCidrs {@code OPAA_LOCAL_ADMIN_ALLOWED_CIDRS}: the networks a local {@code
 *     SYSTEM_ADMIN} may sign in from (Entscheidung 9); empty means no restriction
 */
@ConfigurationProperties(prefix = "opaa.auth.local")
public record LocalAuthProperties(
    @ValidSecret String jwtSecret,
    Duration accessTokenTtl,
    Duration refreshTokenTtl,
    Duration sessionMaxLifetime,
    Duration adminRefreshTokenTtl,
    Duration adminSessionMaxLifetime,
    Boolean cookieSecure,
    String initialAdminPassword,
    String adminReset,
    List<String> adminAllowedCidrs) {

  public static final Duration MAX_REFRESH_TOKEN_TTL = Duration.ofDays(30);
  public static final Duration MAX_SESSION_MAX_LIFETIME = Duration.ofDays(90);
  public static final String ADMIN_RESET_FORCE = "force";
  public static final String INITIAL_ADMIN_PASSWORD_VARIABLE = "OPAA_INITIAL_ADMIN_PASSWORD";
  public static final String ADMIN_RESET_VARIABLE = "OPAA_LOCAL_ADMIN_RESET";
  public static final String ADMIN_ALLOWED_CIDRS_VARIABLE = "OPAA_LOCAL_ADMIN_ALLOWED_CIDRS";

  private static final String PREFIX = "opaa.auth.local.";

  public LocalAuthProperties {
    jwtSecret = jwtSecret == null ? "" : jwtSecret.trim();
    initialAdminPassword = initialAdminPassword == null ? "" : initialAdminPassword.trim();
    adminReset = adminReset == null ? "" : adminReset.trim();
    adminAllowedCidrs = normalizeCidrs(adminAllowedCidrs);
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

  public boolean hasInitialAdminPassword() {
    return !initialAdminPassword.isEmpty();
  }

  public boolean isAdminResetForced() {
    return ADMIN_RESET_FORCE.equalsIgnoreCase(adminReset);
  }

  /** Trimmed, blanks dropped, every entry parseable as an address or CIDR - fail fast otherwise. */
  private static List<String> normalizeCidrs(List<String> cidrs) {
    if (cidrs == null) {
      return List.of();
    }
    List<String> normalized =
        cidrs.stream().filter(c -> c != null && !c.isBlank()).map(String::trim).toList();
    for (String cidr : normalized) {
      try {
        new IpAddressMatcher(cidr);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            PREFIX
                + "admin-allowed-cidrs ("
                + ADMIN_ALLOWED_CIDRS_VARIABLE
                + ") contains no valid address or CIDR range: "
                + cidr,
            e);
      }
    }
    return normalized;
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
