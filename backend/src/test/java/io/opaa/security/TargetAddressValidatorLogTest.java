package io.opaa.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opaa.security.TargetAddressValidator.Scope;
import java.net.URI;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * How a refused target shows in the log: by its host and address unless the caller names hosts
 * otherwise ({@link TargetAddressValidator#namingHostsAs}), as a private library does - for a
 * blocked range and for a name that does not resolve alike. The user-facing refusal is unchanged.
 */
class TargetAddressValidatorLogTest {

  private static final String REFERENCE = "<private library 42>";
  private static final Function<Object, String> WITHHELD = name -> REFERENCE;

  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private final Logger logger = (Logger) LoggerFactory.getLogger(TargetAddressValidator.class);
  private final TargetAddressValidator validator = new TargetAddressValidator(true, List.of());

  @BeforeEach
  void attach() {
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void detach() {
    logger.detachAppender(appender);
  }

  @Test
  void aBlockedRangeIsLoggedWithoutHostOrAddressWhenHostsAreWithheld() {
    assertThatThrownBy(
            () ->
                validator
                    .namingHostsAs(WITHHELD)
                    .validate(URI.create("http://localhost/secret"), Scope.PER_CONNECTION))
        .isInstanceOf(TargetAddressValidator.TargetAddressBlockedException.class)
        .hasMessageContaining("localhost");
    assertThatThrownBy(
            () ->
                validator.namingHostsAs(WITHHELD).validateHost("10.20.30.40", Scope.PER_CONNECTION))
        .isInstanceOf(TargetAddressValidator.TargetAddressBlockedException.class);

    assertThat(lines()).hasSize(2).allSatisfy(line -> assertThat(line).contains(REFERENCE));
    assertThat(lines())
        .noneMatch(line -> line.contains("localhost"))
        .noneMatch(line -> line.contains("127.0.0.1"))
        .noneMatch(line -> line.contains("10.20.30.40"));
  }

  @Test
  void aNameThatDoesNotResolveIsLoggedWithoutItWhenHostsAreWithheld() {
    assertThatThrownBy(
            () ->
                validator
                    .namingHostsAs(WITHHELD)
                    .validate(URI.create("https://hr-intern.invalid/"), Scope.PER_CONNECTION))
        .isInstanceOf(TargetAddressValidator.UnknownTargetHostException.class)
        .hasMessageContaining("hr-intern.invalid");

    assertThat(lines()).singleElement().asString().contains(REFERENCE).doesNotContain("hr-intern");
  }

  @Test
  void hostsShowByDefault() {
    assertThatThrownBy(() -> validator.validateHost("10.20.30.40", Scope.PER_CONNECTION))
        .isInstanceOf(TargetAddressValidator.TargetAddressBlockedException.class);
    assertThatThrownBy(
            () -> validator.validate(URI.create("https://hr-intern.invalid/"), Scope.STORED_TARGET))
        .isInstanceOf(TargetAddressValidator.UnknownTargetHostException.class);

    assertThat(lines())
        .hasSize(2)
        .anyMatch(line -> line.contains("10.20.30.40"))
        .anyMatch(line -> line.contains("hr-intern.invalid"));
  }

  private List<String> lines() {
    return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
  }
}
