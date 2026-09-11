package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.RateLimitService.Decision;
import org.junit.jupiter.api.Test;

class RateLimitServiceTest {

  @Test
  void allowsRequestsWithinLimit() {
    var service = new RateLimitService(3, 60);

    assertThat(service.isAllowed("127.0.0.1")).isTrue();
    assertThat(service.isAllowed("127.0.0.1")).isTrue();
    assertThat(service.isAllowed("127.0.0.1")).isTrue();
  }

  @Test
  void blocksRequestsExceedingLimit() {
    var service = new RateLimitService(2, 60);

    assertThat(service.isAllowed("127.0.0.1")).isTrue();
    assertThat(service.isAllowed("127.0.0.1")).isTrue();
    assertThat(service.isAllowed("127.0.0.1")).isFalse();
  }

  @Test
  void tracksClientsIndependently() {
    var service = new RateLimitService(1, 60);

    assertThat(service.isAllowed("10.0.0.1")).isTrue();
    assertThat(service.isAllowed("10.0.0.2")).isTrue();
    assertThat(service.isAllowed("10.0.0.1")).isFalse();
    assertThat(service.isAllowed("10.0.0.2")).isFalse();
  }

  @Test
  void allowsRequestsAfterWindowExpires() throws InterruptedException {
    var service = new RateLimitService(1, 1);

    assertThat(service.isAllowed("127.0.0.1")).isTrue();
    assertThat(service.isAllowed("127.0.0.1")).isFalse();

    Thread.sleep(1100);

    assertThat(service.isAllowed("127.0.0.1")).isTrue();
  }

  @Test
  void aRejectionSaysWhenTheOldestRequestLeavesTheWindow() {
    // Retry-After is the time until the oldest request in the window expires, rounded up to a
    // whole second and never below one - a client that waits that long is admitted again.
    var service = new RateLimitService(2, 60);

    Decision first = service.tryAcquire("k");
    Decision second = service.tryAcquire("k");
    Decision third = service.tryAcquire("k");

    assertThat(first.allowed()).isTrue();
    assertThat(first.retryAfterSeconds()).isZero();
    assertThat(second.allowed()).isTrue();
    assertThat(third.allowed()).isFalse();
    assertThat(third.retryAfterSeconds()).isBetween(1L, 60L);
  }

  @Test
  void aFloodOfKeysStaysWithinTheCapAndFailsOpenForForgottenKeys() {
    // the cap bounds memory, not correctness: a forgotten key starts a fresh window (documented
    // fail-open); every request is still answered, and the store never exceeds the cap
    var service = new RateLimitService(1, 60, 10);

    for (int i = 0; i < 1_000; i++) {
      assertThat(service.tryAcquire("client-" + i).allowed()).isTrue();
    }

    assertThat(service.trackedKeys()).isLessThanOrEqualTo(10);
    assertThat(service.tryAcquire("client-999999").allowed()).isTrue();
  }

  @Test
  void aRejectionDoesNotCountAsARequest() {
    var service = new RateLimitService(1, 60);

    assertThat(service.tryAcquire("k").allowed()).isTrue();
    assertThat(service.tryAcquire("k").allowed()).isFalse();
    assertThat(service.tryAcquire("k").allowed()).isFalse();
    assertThat(service.tryAcquire("other").allowed()).isTrue();
  }
}
