package io.opaa.indexing.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * One answer of the core is reused for the validity and no longer, a refusal ends every later ask
 * of the run, and a derived value is recomputed only for a changed secret.
 */
class RunCredentialsTest {

  private static final SourceBlock BLOCK =
      new SourceBlock(SourceBlock.Reason.NOT_CONNECTED, "Verwaltende", "Verbindung getrennt");

  private final AtomicLong now = new AtomicLong();
  private final AtomicInteger asks = new AtomicInteger();
  private final List<Secret> answers = new ArrayList<>(List.of(Secret.personal("erstes")));

  private final Supplier<Secret> core =
      () -> {
        asks.incrementAndGet();
        return answers.size() > 1 ? answers.removeFirst() : answers.getFirst();
      };

  private RunCredentials credentials(Duration validity) {
    return new RunCredentials(core, validity, now::get);
  }

  @Test
  void anAnswerIsReusedForTheValidityAndAskedAgainAfterIt() {
    answers.add(Secret.personal("erneuert"));
    RunCredentials credentials = credentials(Duration.ofSeconds(10));

    assertThat(credentials.value()).isEqualTo("erstes");
    now.addAndGet(Duration.ofMillis(9_999).toNanos());
    assertThat(credentials.value()).isEqualTo("erstes");
    assertThat(asks).hasValue(1);

    now.addAndGet(Duration.ofMillis(1).toNanos());
    assertThat(credentials.value()).isEqualTo("erneuert");
    assertThat(asks).hasValue(2);
  }

  @Test
  void withoutValidityEveryAccessAsksTheCore() {
    RunCredentials credentials = credentials(Duration.ZERO);

    credentials.check();
    credentials.check();
    credentials.secret();

    assertThat(asks).hasValue(3);
  }

  @Test
  void aRefusalEndsEveryLaterAskWithoutAskingTheCoreAgain() {
    AtomicInteger refusals = new AtomicInteger();
    RunCredentials credentials =
        new RunCredentials(
            () -> {
              refusals.incrementAndGet();
              throw new SourceConnectionBlockedException(BLOCK);
            },
            Duration.ZERO,
            now::get);

    assertThatThrownBy(credentials::check)
        .isInstanceOf(SourceConnectionBlockedException.class)
        .hasMessage("Verbindung getrennt");
    assertThatThrownBy(credentials::value).isInstanceOf(SourceConnectionBlockedException.class);
    assertThat(refusals).hasValue(1);
  }

  @Test
  void aSecretDiscardedWithinTheValidityEndsTheRunOnceTheValidityIsOver() {
    RunCredentials credentials =
        new RunCredentials(
            () -> {
              if (asks.incrementAndGet() > 1) {
                throw new SourceConnectionBlockedException(BLOCK);
              }
              return Secret.personal("erstes");
            },
            RunCredentials.VALIDITY,
            now::get);

    credentials.check();
    now.addAndGet(RunCredentials.VALIDITY.toNanos());

    assertThatThrownBy(credentials::check).isInstanceOf(SourceConnectionBlockedException.class);
  }

  @Test
  void aDerivedValueIsComputedAgainOnlyForAChangedSecret() {
    answers.add(Secret.personal("erstes"));
    answers.add(Secret.personal("erneuert"));
    AtomicInteger derivations = new AtomicInteger();
    Supplier<String> header =
        credentials(Duration.ZERO)
            .derived(
                secret -> {
                  derivations.incrementAndGet();
                  return "Basic " + secret;
                });

    assertThat(header.get()).isEqualTo("Basic erstes");
    assertThat(header.get()).isEqualTo("Basic erstes");
    assertThat(derivations).hasValue(1);
    assertThat(header.get()).isEqualTo("Basic erneuert");
    assertThat(derivations).hasValue(2);
  }

  @Test
  void noSecretIsNullAndStillAsked() {
    answers.set(0, null);
    RunCredentials credentials = credentials(Duration.ZERO);

    assertThat(credentials.secret()).isNull();
    assertThat(credentials.value()).isNull();
    assertThat(asks).hasValue(2);
  }
}
