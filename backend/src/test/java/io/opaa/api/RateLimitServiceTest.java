package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

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
}
