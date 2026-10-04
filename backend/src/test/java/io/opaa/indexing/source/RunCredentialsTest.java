package io.opaa.indexing.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.test.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * One answer of the core is reused for the validity and no longer - nor past the expiry it carries,
 * nor after an invalidation - a refusal ends every later ask of the run, and a derived value is
 * recomputed only for a changed secret.
 */
class RunCredentialsTest {

  private static final Instant START = Instant.parse("2026-10-04T12:00:00Z");
  private static final SourceBlock BLOCK =
      new SourceBlock(SourceBlock.Reason.NOT_CONNECTED, "Verwaltende", "Verbindung getrennt");

  private final MutableClock clock = new MutableClock(START);
  private final AtomicInteger asks = new AtomicInteger();
  private final List<Secret> answers = new ArrayList<>(List.of(Secret.personal("erstes")));

  private final Supplier<Secret> core =
      () -> {
        asks.incrementAndGet();
        return answers.size() > 1 ? answers.removeFirst() : answers.getFirst();
      };

  private RunCredentials credentials(Duration validity) {
    return new RunCredentials(core, validity, clock);
  }

  @Test
  void anAnswerIsReusedForTheValidityAndAskedAgainAfterIt() {
    answers.add(Secret.personal("erneuert"));
    RunCredentials credentials = credentials(Duration.ofSeconds(10));

    assertThat(credentials.value()).isEqualTo("erstes");
    clock.advance(Duration.ofMillis(9_999));
    assertThat(credentials.value()).isEqualTo("erstes");
    assertThat(asks).hasValue(1);

    clock.advance(Duration.ofMillis(1));
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
  void aTokenIsNotReusedCloserToItsExpiryThanTheMargin() {
    Instant expiresAt = START.plus(RunCredentials.EXPIRY_MARGIN).plusSeconds(4);
    answers.set(0, new Secret(SecretKind.ACCESS_TOKEN, "token", expiresAt));
    answers.add(new Secret(SecretKind.ACCESS_TOKEN, "erneuert", expiresAt.plusSeconds(3600)));
    RunCredentials credentials = credentials(RunCredentials.VALIDITY);

    assertThat(credentials.value()).isEqualTo("token");
    clock.advance(Duration.ofSeconds(3));
    assertThat(credentials.value()).isEqualTo("token");
    clock.advance(Duration.ofSeconds(1));

    assertThat(credentials.value()).as("4 s before the margin, not 10").isEqualTo("erneuert");
    assertThat(asks).hasValue(2);
  }

  @Test
  void aTokenAlreadyWithinTheMarginIsAskedForOnEveryAccess() {
    answers.set(0, new Secret(SecretKind.ACCESS_TOKEN, "token", START.plusSeconds(5)));
    RunCredentials credentials = credentials(RunCredentials.VALIDITY);

    credentials.check();
    credentials.check();

    assertThat(asks).hasValue(2);
  }

  @Test
  void anInvalidatedAnswerIsAskedForAgainAtOnce() {
    answers.add(Secret.personal("erneuert"));
    RunCredentials credentials = credentials(RunCredentials.VALIDITY);

    assertThat(credentials.value()).isEqualTo("erstes");
    credentials.invalidate();

    assertThat(credentials.value()).isEqualTo("erneuert");
    assertThat(credentials.value()).isEqualTo("erneuert");
    assertThat(asks).hasValue(2);
  }

  @Test
  void aRefusalEndsEveryLaterAskWithoutAskingTheCoreAgainEvenAfterAnInvalidation() {
    AtomicInteger refusals = new AtomicInteger();
    RunCredentials credentials =
        new RunCredentials(
            () -> {
              refusals.incrementAndGet();
              throw new SourceConnectionBlockedException(BLOCK);
            },
            Duration.ZERO,
            clock);

    assertThatThrownBy(credentials::check)
        .isInstanceOf(SourceConnectionBlockedException.class)
        .hasMessage("Verbindung getrennt");
    credentials.invalidate();
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
            clock);

    credentials.check();
    clock.advance(RunCredentials.VALIDITY);

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
