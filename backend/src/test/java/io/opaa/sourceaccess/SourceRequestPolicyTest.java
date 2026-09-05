package io.opaa.sourceaccess;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SourceRequestPolicyTest {

  @Test
  void aBlankUserAgentFallsBackToTheTruthfulDefault() {
    assertThat(new SourceRequestPolicy(" ", RateLimitPolicy.NONE, d -> {}).userAgent())
        .isEqualTo("OPAA-Indexer/1.0");
    assertThat(new SourceRequestPolicy(null, RateLimitPolicy.NONE, d -> {}).userAgent())
        .isEqualTo("OPAA-Indexer/1.0");
    assertThat(SourceRequestPolicy.defaults().rateLimit())
        .isEqualTo(RateLimitPolicy.of(6, Duration.ofMinutes(2)));
  }

  @Test
  void headersCarryTheUserAgentAndAuthorizationOnlyWhenGiven() {
    SourceRequestPolicy policy =
        new SourceRequestPolicy("OPAA-Indexer/test", RateLimitPolicy.NONE, d -> {});

    assertThat(policy.headers(null)).containsExactly(Map.entry("User-Agent", "OPAA-Indexer/test"));
    Map<String, String> withAuth = policy.headers("Basic abc");
    assertThat(withAuth)
        .containsEntry("User-Agent", "OPAA-Indexer/test")
        .containsEntry("Authorization", "Basic abc");
    withAuth.put("Accept", "application/json");
    assertThat(policy.headers("Basic abc"))
        .as("a fresh map every time")
        .doesNotContainKey("Accept");
  }

  @Test
  void derivedPoliciesKeepTheUserAgent() {
    Sleeper sleeper = d -> {};
    SourceRequestPolicy policy =
        new SourceRequestPolicy("OPAA-Indexer/test", RateLimitPolicy.NONE, sleeper);
    RateLimitPolicy confluence = RateLimitPolicy.of(3, Duration.ofSeconds(2));

    SourceRequestPolicy derived = policy.withRateLimit(confluence);

    assertThat(derived.userAgent()).isEqualTo("OPAA-Indexer/test");
    assertThat(derived.rateLimit()).isEqualTo(confluence);
    assertThat(derived.sleeper()).isSameAs(sleeper);
    assertThat(derived.rateLimitHandling().policy()).isEqualTo(confluence);
    assertThat(derived.rateLimitHandling().listener()).isSameAs(RateLimitListener.NONE);
  }
}
