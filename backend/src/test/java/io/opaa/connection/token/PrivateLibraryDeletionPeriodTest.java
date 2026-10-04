package io.opaa.connection.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The deletion period of private libraries is a start setting whose upper bound of 90 days no
 * configuration can stretch: a value outside its bounds fails the start.
 */
class PrivateLibraryDeletionPeriodTest {

  private final ApplicationContextRunner context =
      new ApplicationContextRunner().withUserConfiguration(ConnectionLifecycleConfiguration.class);

  @Test
  void withoutAValueTheDeliveredThirtyDaysApply() {
    assertThat(PrivateLibraryDeletionPeriod.defaults().period()).isEqualTo(Duration.ofDays(30));
    context.run(
        started ->
            assertThat(started.getBean(PrivateLibraryDeletionPeriod.class).period())
                .isEqualTo(Duration.ofDays(30)));
  }

  @Test
  void theBoundsThemselvesAreAccepted() {
    assertThat(new PrivateLibraryDeletionPeriod(1).period()).isEqualTo(Duration.ofDays(1));
    assertThat(new PrivateLibraryDeletionPeriod(PrivateLibraryDeletionPeriod.MAX_DAYS).period())
        .isEqualTo(Duration.ofDays(90));
  }

  @Test
  void aValueAboveTheUpperBoundIsRefusedAtTheStart() {
    context
        .withPropertyValues("opaa.connection.private-library-deletion-days=91")
        .run(
            started ->
                assertThat(started)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("opaa.connection.private-library-deletion-days")
                    .hasMessageContaining("91"));
  }

  @Test
  void aValueBelowTheLowerBoundIsRefused() {
    assertThatThrownBy(() -> new PrivateLibraryDeletionPeriod(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("0");
  }
}
