package io.opaa.security;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opaa.api.RateLimitProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Start-up behaviour of the trusted-proxy list (ADR-0033, Entscheidung 9): in the {@code oidc}
 * profile an empty {@code OPAA_RATE_LIMIT_TRUSTED_PROXY_CIDRS} is not an error but a {@code WARN}
 * that spells out the consequence - behind a proxy every client shares one bucket - and a wildcard
 * range refuses the start, because it would make every client a trusted proxy.
 */
class TrustedProxyStartupGuardTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(TrustedProxyStartupGuard.class);

  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private Logger logger;

  @BeforeEach
  void setUp() {
    appender.start();
    logger = (Logger) LoggerFactory.getLogger(TrustedProxyStartupGuard.class);
    logger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(appender);
  }

  @Test
  void oidcProfileWarnsOnceWhenNoProxyIsTrusted() {
    contextRunner
        .withPropertyValues("spring.profiles.active=oidc")
        .run(context -> assertThat(context).hasNotFailed());

    assertThat(appender.list)
        .filteredOn(event -> event.getLevel() == Level.WARN)
        .extracting(ILoggingEvent::getFormattedMessage)
        .hasSize(1)
        .allSatisfy(
            message ->
                assertThat(message)
                    .contains("OPAA_RATE_LIMIT_TRUSTED_PROXY_CIDRS")
                    .contains("X-Forwarded-For")
                    .containsIgnoringCase("one bucket"));
  }

  @Test
  void oidcProfileStaysQuietWithATrustedProxy() {
    contextRunner
        .withPropertyValues(
            "spring.profiles.active=oidc", "opaa.rate-limit.trusted-proxy-cidrs=172.28.0.0/16")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(RateLimitProperties.class).trustedProxyCidrs())
                  .containsExactly("172.28.0.0/16");
            });

    assertThat(appender.list).filteredOn(event -> event.getLevel() == Level.WARN).isEmpty();
  }

  @Test
  void oidcProfileRefusesAWildcardProxyRangeNamingTheVariable() {
    for (String wildcard : new String[] {"0.0.0.0/0", "::/0", "10.0.0.0/8, 0.0.0.0/0"}) {
      contextRunner
          .withPropertyValues(
              "spring.profiles.active=oidc", "opaa.rate-limit.trusted-proxy-cidrs=" + wildcard)
          .run(
              context ->
                  assertThat(context)
                      .as(wildcard)
                      .hasFailed()
                      .getFailure()
                      .rootCause()
                      .hasMessageContaining("OPAA_RATE_LIMIT_TRUSTED_PROXY_CIDRS")
                      .hasMessageContaining("/0"));
    }
  }

  @Test
  void devProfileDoesNotWarn() {
    contextRunner
        .withPropertyValues("spring.profiles.active=dev")
        .run(context -> assertThat(context).hasNotFailed());

    assertThat(appender.list).filteredOn(event -> event.getLevel() == Level.WARN).isEmpty();
  }
}
