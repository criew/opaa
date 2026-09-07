package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.sourceaccess.RateLimitPolicy;
import io.opaa.sourceaccess.SourceRequestPolicy;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * {@code opaa.indexing.http.*} binds to {@link SourceHttpProperties} and yields the shared policy.
 */
class SourceHttpPropertiesTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(Config.class);

  @Test
  void bindsTheSharedKeysAndBuildsThePolicyFromThem() {
    contextRunner
        .withPropertyValues(
            "opaa.indexing.http.user-agent=Behoerde-Indexer/2.0",
            "opaa.indexing.http.max-rate-limit-retries=3",
            "opaa.indexing.http.max-retry-after=30s",
            "opaa.indexing.http.request-budget-per-run=500",
            "opaa.indexing.http.max-rate-limit-wait-per-run=5m")
        .run(
            context -> {
              SourceRequestPolicy policy =
                  context.getBean(SourceHttpProperties.class).toRequestPolicy();
              assertThat(policy.userAgent()).isEqualTo("Behoerde-Indexer/2.0");
              assertThat(policy.rateLimit())
                  .isEqualTo(RateLimitPolicy.of(3, Duration.ofSeconds(30)));
              assertThat(policy.requestBudgetPerRun()).isEqualTo(500);
              assertThat(policy.maxRateLimitWaitPerRun()).isEqualTo(Duration.ofMinutes(5));
            });
  }

  @Test
  void defaultsToTheTruthfulUserAgentAndConfluencesRateLimitNumbersWhenUnset() {
    contextRunner.run(
        context -> {
          SourceRequestPolicy policy =
              context.getBean(SourceHttpProperties.class).toRequestPolicy();
          assertThat(policy.userAgent()).isEqualTo("OPAA-Indexer/1.0");
          assertThat(policy.rateLimit()).isEqualTo(RateLimitPolicy.of(6, Duration.ofMinutes(2)));
          assertThat(policy.requestBudgetPerRun()).as("unbounded by default").isZero();
          assertThat(policy.maxRateLimitWaitPerRun()).isEqualTo(Duration.ofMinutes(15));
        });
  }

  @EnableConfigurationProperties(SourceHttpProperties.class)
  private static class Config {}
}
