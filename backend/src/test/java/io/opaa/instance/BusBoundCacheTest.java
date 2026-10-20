package io.opaa.instance;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.opaa.test.TestInvalidationBus;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BusBoundCacheTest {

  private final TestInvalidationBus bus = TestInvalidationBus.listening();
  private final BusBoundCache<String, Integer> cache =
      new BusBoundCache<>(Caffeine.newBuilder().maximumSize(100).build(), bus);
  private final AtomicInteger loads = new AtomicInteger();

  @Test
  void aListeningBusServesFromTheCache() {
    cache.get("key", key -> loads.incrementAndGet());
    cache.get("key", key -> loads.incrementAndGet());

    assertThat(loads).hasValue(1);
    assertThat(cache.getIfPresent("key")).isEqualTo(1);
  }

  @Test
  void whileTheBusDoesNotListenEveryReadGoesPastTheCacheAndKeepsNothing() {
    cache.get("key", key -> loads.incrementAndGet());
    bus.setListening(false);

    assertThat(cache.get("key", key -> loads.incrementAndGet())).isEqualTo(2);
    assertThat(cache.get("key", key -> loads.incrementAndGet())).isEqualTo(3);
    assertThat(cache.getIfPresent("key")).isNull();

    bus.setListening(true);
    assertThat(cache.getIfPresent("key")).as("nothing read while deaf was kept").isEqualTo(1);
  }

  @Test
  void aLoadOvertakenByADropIsReturnedButNotKept() throws Exception {
    CountDownLatch loading = new CountDownLatch(1);
    CountDownLatch dropped = new CountDownLatch(1);
    CompletableFuture<Integer> load =
        CompletableFuture.supplyAsync(
            () ->
                cache.get(
                    "key",
                    key -> {
                      // the drop of an announcement arrives while this load reads the old state
                      loading.countDown();
                      await(dropped);
                      return loads.incrementAndGet();
                    }));
    loading.await(5, TimeUnit.SECONDS);
    cache.invalidateAll();
    dropped.countDown();

    assertThat(load.get(5, TimeUnit.SECONDS)).isEqualTo(1);
    assertThat(cache.getIfPresent("key")).isNull();
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
