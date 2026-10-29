package io.opaa.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolver.LookupPolicy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ResolutionPins} on its own clock and name server (ADR-0063): the exact pin after a check,
 * the guard that filters every later resolution until restart, the full table that refuses new
 * names, and the release by the guarding policy only.
 */
class ResolutionPinsTest {

  private static final LookupPolicy ANY = LookupPolicy.of(LookupPolicy.IPV4 | LookupPolicy.IPV6);
  private static final String HOST = "rebind.example";
  private static final int SOURCES = 1;
  private static final int MODELS = 2;
  private static final int CAPACITY = 3;
  private static final int CONNECTION_CAPACITY = 5;

  private final AtomicLong now = new AtomicLong(1_000_000L);
  private final Answers answers = new Answers();
  private ResolutionPins pins;

  @BeforeEach
  void setUp() {
    pins = new ResolutionPins(now::get, CAPACITY, CONNECTION_CAPACITY);
    pins.installBuiltin(answers);
  }

  @Test
  void withinThePinAGuardedNameAnswersTheVettedAddressesWhateverTheNameServerSaysNow()
      throws Exception {
    check(HOST, "203.0.113.7");
    answers.next("127.0.0.1");

    assertThat(pins.lookup(HOST, ANY)).containsExactly(ip("203.0.113.7"));
  }

  /** Regression guard: a check within the pin resolves afresh, so a legitimate change arrives. */
  @Test
  void aRepeatedCheckWithinThePinFollowsADnsChange() throws Exception {
    check(HOST, "203.0.113.7");
    later(Duration.ofSeconds(30));
    answers.next("198.51.100.4");

    ResolutionPins.Answer answer = pins.resolveForCheck(HOST);
    assertThat(answer.addresses()).containsExactly(ip("198.51.100.4"));
    pins.admit(HOST, SOURCES, answer);
    assertThat(pins.lookup(HOST, ANY)).containsExactly(ip("198.51.100.4"));
  }

  /** Regression guard: the guard does not expire, a day after the check included. */
  @Test
  void longAfterItsCheckAGuardedNameNeverResolvesIntoABlockedRange() throws Exception {
    check(HOST, "203.0.113.7");
    later(Duration.ofHours(25));

    answers.next("10.0.0.5");
    assertThatThrownBy(() -> pins.lookup(HOST, ANY))
        .isInstanceOf(UnknownHostException.class)
        .hasMessageContaining(HOST);
    answers.next("198.51.100.4", "169.254.169.254");
    assertThat(pins.lookup(HOST, ANY)).containsExactly(ip("198.51.100.4"));
  }

  @Test
  void afterThePinAGuardedNameFollowsAPublicChangeAtOnce() throws Exception {
    check(HOST, "203.0.113.7");
    later(ResolutionPins.PIN_LIFETIME);
    answers.next("198.51.100.4");

    assertThat(pins.lookup(HOST, ANY)).containsExactly(ip("198.51.100.4"));
  }

  /** Regression guard: a full table refuses the new name and keeps every existing guard. */
  @Test
  void aFullTableRefusesANewNameAndDropsNoGuard() throws Exception {
    check("a.example", "203.0.113.1");
    check("b.example", "203.0.113.2");
    check("c.example", "203.0.113.3");

    assertThat(pins.admit("d.example", SOURCES, answer("203.0.113.4"))).isFalse();
    assertThat(pins.refusedChecks()).isOne();
    assertThat(pins.guardedNames()).isEqualTo(CAPACITY);
    assertThat(pins.admit("a.example", SOURCES, answer("203.0.113.9"))).isTrue();
    later(ResolutionPins.PIN_LIFETIME);
    answers.next("10.0.0.5");
    for (String guarded : List.of("a.example", "b.example", "c.example")) {
      assertThatThrownBy(() -> pins.lookup(guarded, ANY)).isInstanceOf(UnknownHostException.class);
    }
    assertThat(pins.lookup("d.example", ANY)).containsExactly(ip("10.0.0.5"));
  }

  @Test
  void aReleaseEndsOnlyTheGuardOfItsOwnPolicy() throws Exception {
    check(HOST, "203.0.113.7");
    pins.admit(HOST, MODELS, answer("203.0.113.7"));
    later(ResolutionPins.PIN_LIFETIME);
    answers.next("10.0.0.5");

    pins.release(HOST, MODELS);
    assertThatThrownBy(() -> pins.lookup(HOST, ANY)).isInstanceOf(UnknownHostException.class);
    pins.release(HOST, SOURCES);
    assertThat(pins.lookup(HOST, ANY)).containsExactly(ip("10.0.0.5"));
  }

  @Test
  void anUnguardedNameIsAnsweredByTheNameServerUnchanged() throws Exception {
    answers.next("10.0.0.5");

    assertThat(pins.lookup("database.internal", ANY)).containsExactly(ip("10.0.0.5"));
  }

  @Test
  void caseAndATrailingRootDotNameTheSameGuard() throws Exception {
    check("Rebind.Example.", "203.0.113.7");
    answers.next("127.0.0.1");

    assertThat(pins.lookup(HOST, ANY)).containsExactly(ip("203.0.113.7"));
    assertThat(pins.lookup("REBIND.example.", ANY)).containsExactly(ip("203.0.113.7"));
  }

  /** A character outside ASCII never folds onto an ASCII name, unlike a locale lower case. */
  @Test
  void aNameOutsideAsciiHasNoKeyAndIsNeverGuarded() throws Exception {
    check("key.example", "203.0.113.7");

    assertThat(ResolutionPins.key("Key.example")).isNull();
    assertThat(pins.admit("bücher.example", SOURCES, answer("203.0.113.8"))).isFalse();
    answers.next("10.0.0.5");
    assertThat(pins.lookup("Key.example", ANY)).containsExactly(ip("10.0.0.5"));
  }

  @Test
  void thePinAnswersOnlyTheFamiliesAskedForInTheOrderAskedFor() throws Exception {
    pins.admit(HOST, SOURCES, answer("2001:db8::7", "203.0.113.7"));

    assertThat(pins.lookup(HOST, LookupPolicy.of(LookupPolicy.IPV4)))
        .containsExactly(ip("203.0.113.7"));
    assertThat(
            pins.lookup(
                HOST,
                LookupPolicy.of(LookupPolicy.IPV4 | LookupPolicy.IPV6 | LookupPolicy.IPV4_FIRST)))
        .containsExactly(ip("203.0.113.7"), ip("2001:db8::7"));
    assertThat(
            pins.lookup(
                HOST,
                LookupPolicy.of(LookupPolicy.IPV4 | LookupPolicy.IPV6 | LookupPolicy.IPV6_FIRST)))
        .containsExactly(ip("2001:db8::7"), ip("203.0.113.7"));
  }

  @Test
  void equalAllowlistsShareOnePolicyAndDifferentOnesDoNot() {
    assertThat(ResolutionPins.policyOf(List.of("b.example", " A.example ")))
        .isEqualTo(ResolutionPins.policyOf(List.of("a.example", "b.example")));
    assertThat(ResolutionPins.policyOf(List.of("a.example")))
        .isNotEqualTo(ResolutionPins.policyOf(List.of("a.example:443")));
  }

  /** Regression guard: checks within the reuse window cost one query, a change arrives after it. */
  @Test
  void checksWithinTheReuseWindowShareOneQueryAndAChangeArrivesAfterIt() throws Exception {
    answers.next("203.0.113.7");
    pins.pinConnection(HOST, pins.resolveForCheck(HOST));
    answers.next("198.51.100.4");
    later(ResolutionPins.ANSWER_REUSE.minusMillis(1));

    assertThat(pins.resolveForCheck(HOST).addresses()).containsExactly(ip("203.0.113.7"));
    assertThat(answers.asked).isOne();
    later(Duration.ofMillis(1));
    assertThat(pins.resolveForCheck(HOST).addresses()).containsExactly(ip("198.51.100.4"));
    assertThat(answers.asked).isEqualTo(2);
  }

  /** A reused answer keeps its fetch time: the pin expires as early as the first check's. */
  @Test
  void aReusedAnswerDoesNotExtendThePin() throws Exception {
    answers.next("203.0.113.7");
    pins.pinConnection(HOST, pins.resolveForCheck(HOST));
    later(Duration.ofSeconds(4));
    pins.pinConnection(HOST, pins.resolveForCheck(HOST));
    answers.next("198.51.100.4");

    later(ResolutionPins.PIN_LIFETIME.minusSeconds(4));
    assertThat(pins.lookup(HOST, ANY)).containsExactly(ip("198.51.100.4"));
  }

  /** Regression guard: checks before a connection never take a place in the guard table. */
  @Test
  void aFloodOfConnectionChecksLeavesTheGuardTableFreeForStoredTargets() throws Exception {
    for (int i = 0; i < 1000; i++) {
      answers.next("203.0.113.7");
      String host = "hop" + i + ".example";
      pins.pinConnection(host, pins.resolveForCheck(host));
    }

    assertThat(pins.guardedNames()).isZero();
    check("model.example", "203.0.113.8");
    assertThat(pins.guardedNames()).isOne();
  }

  @Test
  void aConnectionPinIsExactForItsLifetimeThenTheNameIsUnguarded() throws Exception {
    answers.next("203.0.113.7");
    pins.pinConnection(HOST, pins.resolveForCheck(HOST));
    answers.next("127.0.0.1");

    assertThat(pins.lookup(HOST, ANY)).containsExactly(ip("203.0.113.7"));
    later(ResolutionPins.PIN_LIFETIME);
    assertThat(pins.lookup(HOST, ANY)).containsExactly(ip("127.0.0.1"));
  }

  @Test
  void theConnectionTableDropsItsOldestPinWhenFull() throws Exception {
    for (int i = 0; i <= CONNECTION_CAPACITY; i++) {
      answers.next("203.0.113.7");
      String host = "hop" + i + ".example";
      pins.pinConnection(host, pins.resolveForCheck(host));
    }
    answers.next("127.0.0.1");

    assertThat(pins.lookup("hop0.example", ANY)).containsExactly(ip("127.0.0.1"));
    assertThat(pins.lookup("hop1.example", ANY)).containsExactly(ip("203.0.113.7"));
  }

  /** What the validator does for a stored target: a fresh answer, then the guard with it. */
  private void check(String host, String address) throws UnknownHostException {
    answers.next(address);
    assertThat(pins.admit(host, SOURCES, pins.resolveForCheck(host))).isTrue();
  }

  private ResolutionPins.Answer answer(String... literals) {
    return new ResolutionPins.Answer(
        Stream.of(literals).map(ResolutionPinsTest::ip).toList(), now.get());
  }

  private void later(Duration duration) {
    now.addAndGet(duration.toNanos());
  }

  private static InetAddress ip(String literal) {
    try {
      return InetAddress.getByName(literal);
    } catch (UnknownHostException e) {
      throw new IllegalArgumentException(literal, e);
    }
  }

  /** Answers every lookup with the addresses last queued. */
  private static final class Answers implements InetAddressResolver {

    private volatile List<InetAddress> current = List.of();
    private int asked;

    void next(String... literals) {
      current = Stream.of(literals).map(ResolutionPinsTest::ip).toList();
    }

    @Override
    public Stream<InetAddress> lookupByName(String host, LookupPolicy lookupPolicy)
        throws UnknownHostException {
      asked++;
      if (current.isEmpty()) {
        throw new UnknownHostException(host);
      }
      return current.stream();
    }

    @Override
    public String lookupByAddress(byte[] addr) throws UnknownHostException {
      throw new UnknownHostException();
    }
  }
}
