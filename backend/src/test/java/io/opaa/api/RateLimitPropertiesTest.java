package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.RateLimitProperties.LocalAuthLimit;
import io.opaa.api.RateLimitProperties.LocalAuthLimits;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link RateLimitProperties}: the trusted-proxy list is trimmed, validated and refuses a wildcard
 * range; the local-auth limits carry the ADR-0033 defaults (Entscheidung 9) when nothing is
 * configured, and a limit below one is refused.
 */
class RateLimitPropertiesTest {

  private static final RateLimitProperties.EndpointLimit ANY =
      new RateLimitProperties.EndpointLimit(1, 1, 1);

  private static RateLimitProperties properties(List<String> cidrs, LocalAuthLimits localAuth) {
    return new RateLimitProperties(true, cidrs, ANY, ANY, ANY, ANY, ANY, localAuth);
  }

  @Test
  void trustedProxiesDefaultToNoneAndAreTrimmed() {
    assertThat(properties(null, null).trustedProxyCidrs()).isEmpty();
    assertThat(
            properties(List.of(" 10.0.0.0/8 ", "", "  ", "2001:db8::/32"), null)
                .trustedProxyCidrs())
        .containsExactly("10.0.0.0/8", "2001:db8::/32");
  }

  @Test
  void refusesAWildcardOrUnparseableProxyRangeNamingTheVariable() {
    for (String bad : List.of("0.0.0.0/0", "::/0", "kein-netz", "10.0.0.0/33")) {
      assertThatThrownBy(() -> properties(List.of("10.0.0.0/8", bad), null))
          .as(bad)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(RateLimitProperties.TRUSTED_PROXY_CIDRS_VARIABLE)
          .hasMessageContaining(bad);
    }
  }

  @Test
  void localAuthLimitsCarryTheAdrDefaults() {
    LocalAuthLimits limits = properties(null, null).localAuth();

    assertThat(limits.login().maxRequests()).isEqualTo(10);
    assertThat(limits.login().windowSeconds()).isEqualTo(60);
    assertThat(limits.login().hasGlobalLimit()).isTrue();
    assertThat(limits.refresh().maxRequests()).isEqualTo(30);
    assertThat(limits.refresh().windowSeconds()).isEqualTo(60);
    assertThat(limits.refresh().hasGlobalLimit()).isFalse();
    assertThat(limits.changePassword().maxRequests()).isEqualTo(5);
    assertThat(limits.changePassword().windowSeconds()).isEqualTo(300);
    assertThat(limits.register().maxRequests()).isEqualTo(5);
    assertThat(limits.register().windowSeconds()).isEqualTo(3600);
    assertThat(limits.register().hasGlobalLimit()).isTrue();
    assertThat(limits.register().maxRequestsPerAddress()).isEqualTo(3);
    assertThat(limits.forgotPassword().maxRequests()).isEqualTo(5);
    assertThat(limits.forgotPassword().windowSeconds()).isEqualTo(3600);
    assertThat(limits.forgotPassword().hasGlobalLimit()).isTrue();
    assertThat(limits.forgotPassword().maxRequestsPerAddress()).isEqualTo(3);
    assertThat(limits.setPassword().maxRequests()).isEqualTo(10);
    assertThat(limits.setPassword().windowSeconds()).isEqualTo(900);
    assertThat(limits.setPassword().hasGlobalLimit()).isFalse();
    assertThat(limits.setPassword().hasAddressLimit()).isFalse();
  }

  @Test
  void aPartiallyConfiguredLocalAuthBlockKeepsTheDefaultsForTheRest() {
    LocalAuthLimits limits =
        properties(
                null,
                new LocalAuthLimits(
                    new LocalAuthLimit(3, 30, null, null), null, null, null, null, null))
            .localAuth();

    assertThat(limits.login().maxRequests()).isEqualTo(3);
    assertThat(limits.login().hasGlobalLimit()).isFalse();
    assertThat(limits.refresh().maxRequests()).isEqualTo(30);
  }

  @Test
  void refusesALimitBelowOne() {
    assertThatThrownBy(() -> new LocalAuthLimit(0, 60, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxRequests");
    assertThatThrownBy(() -> new LocalAuthLimit(1, 0, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("windowSeconds");
    assertThatThrownBy(() -> new LocalAuthLimit(1, 60, 0, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("globalMaxRequests");
    assertThatThrownBy(() -> new LocalAuthLimit(1, 60, null, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxRequestsPerAddress");
  }
}
