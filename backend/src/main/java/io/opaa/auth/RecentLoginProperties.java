package io.opaa.auth;

import io.opaa.compliance.InstallationWide;
import io.opaa.compliance.NoDataImpact;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code opaa.auth.recent-login.*}: how old the sign-in behind a token may be for an operation
 * marked {@link RequiresRecentLogin}. A non-positive age refuses the start, naming the variable.
 *
 * @param maxAge longest time since the sign-in ({@code auth_time}) that still counts as a recent
 *     one (default 5 minutes)
 */
@InstallationWide
@NoDataImpact("a confirmation window of the sign-in; it keeps no data")
@ConfigurationProperties(prefix = "opaa.auth.recent-login")
public record RecentLoginProperties(
    @NoDataImpact("how long a confirmed sign-in counts as recent; it keeps no data")
        Duration maxAge) {

  public static final String MAX_AGE_VARIABLE = "OPAA_AUTH_RECENT_LOGIN_MAX_AGE";

  public RecentLoginProperties {
    maxAge = maxAge != null ? maxAge : Duration.ofMinutes(5);
    if (maxAge.isZero() || maxAge.isNegative()) {
      throw new IllegalArgumentException(
          "opaa.auth.recent-login.max-age ("
              + MAX_AGE_VARIABLE
              + ") must be positive, got "
              + maxAge);
    }
  }
}
