package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The {@code dev} profile makes every request a system administrator, so it refuses to start when
 * the Compose stack publishes the frontend beyond loopback, unless that is confirmed explicitly.
 */
class DevExposureGuardTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(PropertiesBinding.class, DevExposureGuard.class);

  @EnableConfigurationProperties(AuthProperties.class)
  static class PropertiesBinding {}

  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private Logger logger;

  @BeforeEach
  void setUp() {
    appender.start();
    logger = (Logger) LoggerFactory.getLogger(DevExposureGuard.class);
    logger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(appender);
  }

  @ParameterizedTest
  @ValueSource(strings = {"0.0.0.0", "::", "192.168.10.5", "[2001:db8::1]"})
  void devProfileRefusesAFrontendPublishedBeyondLoopback(String address) {
    contextRunner
        .withPropertyValues(
            "spring.profiles.active=dev", "opaa.auth.dev.published-address=" + address)
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining(address)
                    .hasMessageContaining("OPAA_FRONTEND_BIND_ADDRESS")
                    .hasMessageContaining("OPAA_AUTH_DEV_ALLOW_REMOTE=true"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"127.0.0.1", "127.0.0.2", "::1", "[::1]", "localhost", " "})
  void devProfileStartsOnLoopbackOrOutsideTheStack(String address) {
    contextRunner
        .withPropertyValues(
            "spring.profiles.active=dev", "opaa.auth.dev.published-address=" + address)
        .run(context -> assertThat(context).hasNotFailed());
    assertThat(appender.list).noneMatch(event -> event.getLevel() == Level.WARN);
  }

  @Test
  void devProfileStartsWithoutAnyAddress() {
    contextRunner
        .withPropertyValues("spring.profiles.active=dev")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void confirmedExposureStartsAndWarns() {
    contextRunner
        .withPropertyValues(
            "spring.profiles.active=dev",
            "opaa.auth.dev.published-address=0.0.0.0",
            "opaa.auth.dev.allow-remote=true")
        .run(context -> assertThat(context).hasNotFailed());

    assertThat(appender.list)
        .filteredOn(event -> event.getLevel() == Level.WARN)
        .extracting(ILoggingEvent::getFormattedMessage)
        .singleElement()
        .satisfies(
            message -> assertThat(message).contains("0.0.0.0").contains("system administrator"));
  }

  @Test
  void oidcProfileIsNotJudged() {
    contextRunner
        .withPropertyValues(
            "spring.profiles.active=oidc", "opaa.auth.dev.published-address=0.0.0.0")
        .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(DevExposureGuard.class));
  }
}
