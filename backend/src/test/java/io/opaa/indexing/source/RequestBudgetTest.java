package io.opaa.indexing.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.sourceaccess.RateLimitPolicy;
import io.opaa.sourceaccess.SourceRequestMeter;
import io.opaa.sourceaccess.SourceRequestPolicy;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The two bounds of one run - requests and waiting time - and how they end it. */
class RequestBudgetTest {

  private final SourceRequestMeter meter = new SourceRequestMeter();

  @Test
  void chargeCountsUpToTheBudgetAndRefusesTheNextCallUncounted() {
    RequestBudget budget = new RequestBudget(meter, 2, null);

    budget.charge();
    budget.charge();

    assertThatThrownBy(budget::charge)
        .isInstanceOf(RequestBudgetExhaustedException.class)
        .hasMessage("Anfragebudget von 2 Anfragen erschöpft");
    assertThat(meter.requests()).isEqualTo(2);
  }

  @Test
  void aBudgetOfZeroNeverRefuses() {
    RequestBudget budget = new RequestBudget(meter, 0, null);

    for (int i = 0; i < 100; i++) {
      budget.charge();
    }

    assertThat(meter.requests()).isEqualTo(100);
  }

  @Test
  void everyAttemptOfAFetchIsChargedLikeACall() {
    RequestBudget budget = new RequestBudget(meter, 1, null);

    budget.sending();

    assertThatThrownBy(budget::sending).isInstanceOf(RequestBudgetExhaustedException.class);
    assertThat(meter.requests()).isEqualTo(1);
  }

  @Test
  void aWaitThatWouldCrossTheCapIsRefusedBeforeItIsCountedOrSlept() {
    RequestBudget budget = new RequestBudget(meter, 0, Duration.ofSeconds(3));

    budget.throttled(429, Duration.ofSeconds(2));
    budget.throttled(429, Duration.ofSeconds(1));

    assertThatThrownBy(() -> budget.throttled(429, Duration.ofSeconds(1)))
        .isInstanceOf(RequestBudgetExhaustedException.class)
        .hasMessage("Deckel der 429-Wartezeit von 3 Sekunden je Lauf erreicht");
    assertThat(meter.throttles()).as("the refused wait is not counted").isEqualTo(2);
    assertThat(meter.throttledTime()).isEqualTo(Duration.ofSeconds(3));
  }

  @Test
  void withoutACapEveryWaitIsCounted() {
    RequestBudget budget = new RequestBudget(meter, 0, Duration.ZERO);

    assertThatCode(() -> budget.throttled(429, Duration.ofHours(5))).doesNotThrowAnyException();
    assertThat(meter.throttledTime()).isEqualTo(Duration.ofHours(5));
  }

  @Test
  void theInsufficiencyNoteNamesTheBoundAndTheAdvice() {
    assertThat(RequestBudgetExhaustedException.requests(50).insufficiencyNote("Budget anheben."))
        .isEqualTo(
            "Das Anfragebudget von 50 Anfragen reicht für diese Bibliothek nicht aus: Budget"
                + " anheben.");
    assertThat(
            RequestBudgetExhaustedException.throttleWait(Duration.ofMinutes(15))
                .insufficiencyNote("Zeitplan entzerren."))
        .isEqualTo(
            "Der Deckel der 429-Wartezeit von 15 Minuten reicht für diese Bibliothek nicht aus:"
                + " Zeitplan entzerren.");
  }

  @Test
  void forRunTakesBothBoundsFromThePolicyAndStartsAFreshMeter() {
    SourceRequestPolicy policy =
        new SourceRequestPolicy("OPAA-Indexer/test", RateLimitPolicy.NONE, d -> {})
            .withRunBounds(3, Duration.ofSeconds(1));

    RequestBudget budget = RequestBudget.forRun(policy);

    assertThat(budget.meter().requests()).isZero();
    assertThat(budget.requestBudget()).isEqualTo(3);
    assertThatThrownBy(() -> budget.throttled(429, Duration.ofSeconds(2)))
        .isInstanceOf(RequestBudgetExhaustedException.class);
    RequestBudget unbounded = RequestBudget.unbounded();
    assertThat(unbounded.requestBudget()).isZero();
    assertThatCode(() -> unbounded.throttled(429, Duration.ofDays(1))).doesNotThrowAnyException();
  }
}
