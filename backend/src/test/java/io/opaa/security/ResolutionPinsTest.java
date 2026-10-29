package io.opaa.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolver.LookupPolicy;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ResolutionPins} on its own clock and name server: what a bound name answers within and
 * after the pin, when a binding ends, and that an unbound name passes unchanged (ADR-0063).
 */
class ResolutionPinsTest {

  private static final LookupPolicy ANY = LookupPolicy.of(LookupPolicy.IPV4 | LookupPolicy.IPV6);
  private static final String HOST = "rebind.example";

  private final AtomicLong now = new AtomicLong(1_000_000L);
  private final Answers answers = new Answers();
  private ResolutionPins pins;

  @BeforeEach
  void setUp() {
    pins = new ResolutionPins(now::get);
    pins.installBuiltin(answers);
  }

  @Test
  void aBoundNameAnswersTheVettedAddressesWhateverTheNameServerSaysNow() throws Exception {
    pins.pin(HOST, List.of(ip("203.0.113.7")));
    answers.next("127.0.0.1");

    assertThat(pins.lookup(HOST, ANY)).containsExactly(ip("203.0.113.7"));
    assertThat(answers.asked).isZero();
  }

  @Test
  void afterThePinABoundNameIsResolvedAfreshAndRefusedWhenTheAnswerIsBlocked() {
    pins.pin(HOST, List.of(ip("203.0.113.7")));
    later(ResolutionPins.PIN_LIFETIME.toNanos());
    answers.next("10.0.0.5");

    assertThatThrownBy(() -> pins.lookup(HOST, ANY))
        .isInstanceOf(UnknownHostException.class)
        .hasMessageContaining(HOST);
    answers.next("192.168.1.9");
    assertThatThrownBy(() -> pins.lookup(HOST, ANY)).isInstanceOf(UnknownHostException.class);
  }

  @Test
  void afterThePinAFreshPublicAnswerIsPinnedInItsPlace() throws Exception {
    pins.pin(HOST, List.of(ip("203.0.113.7")));
    later(ResolutionPins.PIN_LIFETIME.toNanos());
    answers.next("198.51.100.4");

    assertThat(pins.lookup(HOST, ANY)).containsExactly(ip("198.51.100.4"));
    answers.next("127.0.0.1");
    assertThat(pins.lookup(HOST, ANY)).containsExactly(ip("198.51.100.4"));
  }

  @Test
  void aBindingEndsAfterItsLifetimeAndTheNameIsUnboundAgain() throws Exception {
    pins.pin(HOST, List.of(ip("203.0.113.7")));
    later(ResolutionPins.BINDING_LIFETIME.toNanos() + 1);
    answers.next("10.0.0.5");

    assertThat(pins.lookup(HOST, ANY)).containsExactly(ip("10.0.0.5"));
  }

  @Test
  void aReleasedNameIsUnbound() throws Exception {
    pins.pin(HOST, List.of(ip("203.0.113.7")));
    pins.release(HOST);
    answers.next("10.0.0.5");

    assertThat(pins.lookup(HOST, ANY)).containsExactly(ip("10.0.0.5"));
  }

  @Test
  void anUnboundNameIsAnsweredByTheNameServerUnchanged() throws Exception {
    answers.next("10.0.0.5");

    assertThat(pins.lookup("database.internal", ANY)).containsExactly(ip("10.0.0.5"));
  }

  @Test
  void caseAndATrailingRootDotNameTheSameBinding() throws Exception {
    pins.pin("Rebind.Example.", List.of(ip("203.0.113.7")));
    answers.next("127.0.0.1");

    assertThat(pins.lookup(HOST, ANY)).containsExactly(ip("203.0.113.7"));
    assertThat(pins.lookup("REBIND.example.", ANY)).containsExactly(ip("203.0.113.7"));
  }

  @Test
  void thePinAnswersOnlyTheFamiliesAskedForInTheOrderAskedFor() throws Exception {
    pins.pin(HOST, List.of(ip("2001:db8::7"), ip("203.0.113.7")));

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
  void aCheckReusesAFreshPinAndResolvesAfreshAfterIt() throws Exception {
    pins.pin(HOST, List.of(ip("203.0.113.7")));
    answers.next("198.51.100.4");

    assertThat(pins.resolveForCheck(HOST)).containsExactly(ip("203.0.113.7"));
    later(ResolutionPins.PIN_LIFETIME.toNanos());
    assertThat(pins.resolveForCheck(HOST)).containsExactly(ip("198.51.100.4"));
  }

  @Test
  void atTheCapTheBindingWithTheOldestCheckGivesWay() throws Exception {
    for (int i = 0; i < ResolutionPins.MAX_BINDINGS; i++) {
      pins.pin("name" + i + ".example", List.of(ip("203.0.113.7")));
      later(1);
    }
    pins.pin(HOST, List.of(ip("203.0.113.8")));
    answers.next("10.0.0.5");

    assertThat(pins.lookup("name0.example", ANY)).containsExactly(ip("10.0.0.5"));
    assertThat(pins.lookup("name1.example", ANY)).containsExactly(ip("203.0.113.7"));
    assertThat(pins.lookup(HOST, ANY)).containsExactly(ip("203.0.113.8"));
  }

  private void later(long nanos) {
    now.addAndGet(nanos);
  }

  private static InetAddress ip(String literal) {
    try {
      return InetAddress.getByName(literal);
    } catch (UnknownHostException e) {
      throw new IllegalArgumentException(literal, e);
    }
  }

  /** Answers each lookup with the next queued address, the last one repeating. */
  private static final class Answers implements InetAddressResolver {

    private final Deque<InetAddress> queue = new ArrayDeque<>();
    private int asked;

    void next(String literal) {
      queue.clear();
      queue.add(ip(literal));
    }

    @Override
    public Stream<InetAddress> lookupByName(String host, LookupPolicy lookupPolicy)
        throws UnknownHostException {
      asked++;
      if (queue.isEmpty()) {
        throw new UnknownHostException(host);
      }
      return Stream.of(queue.peek());
    }

    @Override
    public String lookupByAddress(byte[] addr) throws UnknownHostException {
      throw new UnknownHostException();
    }
  }
}
