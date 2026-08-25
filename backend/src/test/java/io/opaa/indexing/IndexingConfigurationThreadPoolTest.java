package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.library.UploadProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Verifies that {@code uploadTaskExecutor} and {@code indexingTaskExecutor} are sized from two
 * genuinely independent property blocks (#614, PR #589 second review round, finding 1) - before
 * this fix, both beans read {@link IndexingProperties#threadPool()}, so an operator raising {@code
 * opaa.indexing.thread-pool.max-size} to cap total indexing concurrency actually doubled it,
 * because the upload pool grew by the same amount unnoticed.
 */
class IndexingConfigurationThreadPoolTest {

  private final IndexingConfiguration configuration = new IndexingConfiguration();

  @Test
  void uploadPoolSizeIsUnaffectedByRaisingTheIndexingPoolSize() {
    IndexingProperties indexingProperties =
        new IndexingProperties(
            0, 0, 0, new IndexingProperties.ThreadPool(9, 20, 99), null, null, null, null, 0);
    UploadProperties uploadProperties =
        new UploadProperties(null, 0, new UploadProperties.ThreadPool(1, 2, 3), 0, 0);

    TaskExecutor indexingExecutor = configuration.indexingTaskExecutor(indexingProperties);
    TaskExecutor uploadExecutor = configuration.uploadTaskExecutor(uploadProperties);

    assertThat(indexingExecutor).isInstanceOf(ThreadPoolTaskExecutor.class);
    assertThat(uploadExecutor).isInstanceOf(ThreadPoolTaskExecutor.class);
    ThreadPoolTaskExecutor indexing = (ThreadPoolTaskExecutor) indexingExecutor;
    ThreadPoolTaskExecutor upload = (ThreadPoolTaskExecutor) uploadExecutor;

    assertThat(indexing.getCorePoolSize()).isEqualTo(9);
    assertThat(indexing.getMaxPoolSize()).isEqualTo(20);

    // The upload pool keeps its own, much smaller configuration - unaffected by the indexing
    // pool's much larger one above, unlike before #614 where both read the same properties.
    assertThat(upload.getCorePoolSize()).isEqualTo(1);
    assertThat(upload.getMaxPoolSize()).isEqualTo(2);
    assertThat(upload.getQueueCapacity()).isEqualTo(3);
  }

  // #735 review, nit 7: embeddingTaskExecutor's own wiring (core == max == embeddingConcurrency,
  // an effectively unbounded queue) never had a direct test - IndexingConfigurationThreadPoolTest
  // is the established home for exactly this kind of bean-wiring assertion.
  @Test
  void embeddingTaskExecutorIsFixedSizeAtTheConfiguredConcurrency() {
    IndexingProperties indexingProperties =
        new IndexingProperties(0, 0, 0, null, null, null, null, null, 7);

    TaskExecutor embeddingExecutor = configuration.embeddingTaskExecutor(indexingProperties);

    assertThat(embeddingExecutor).isInstanceOf(ThreadPoolTaskExecutor.class);
    ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) embeddingExecutor;
    assertThat(executor.getCorePoolSize()).isEqualTo(7);
    assertThat(executor.getMaxPoolSize()).isEqualTo(7);
  }

  @Test
  void embeddingConcurrencyDefaultsWhenNotConfigured() {
    // IndexingProperties' own compact constructor normalises <= 0 to the documented default (3),
    // exactly like chunkSize/batchSize already do - embeddingTaskExecutor simply reads whatever
    // the property already normalised, so this pins the two together.
    IndexingProperties indexingProperties =
        new IndexingProperties(0, 0, 0, null, null, null, null, null, 0);

    assertThat(indexingProperties.embeddingConcurrency()).isEqualTo(3);
    ThreadPoolTaskExecutor executor =
        (ThreadPoolTaskExecutor) configuration.embeddingTaskExecutor(indexingProperties);
    assertThat(executor.getCorePoolSize()).isEqualTo(3);
  }

  @Test
  void embeddingConcurrencyAboveTheUpperBoundIsRejected() {
    assertThatThrownBy(() -> new IndexingProperties(0, 0, 0, null, null, null, null, null, 33))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("embeddingConcurrency");
  }
}
