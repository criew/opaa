package io.opaa.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The corpus of the parser fuzzing in every build: each admitted format has a seed, the committed
 * seeds are exactly what the generator writes, the corpus stays small, and every seed passes the
 * production path once without a finding - so an input a campaign found and the corpus took up is a
 * regression test. One production path serves every test.
 */
class FuzzCorpusTest {

  /** The most the committed corpus may hold; a seed is a starting point, not a load test. */
  static final long MAX_CORPUS_BYTES = 2L * 1024 * 1024;

  /** Admitted formats without a seed, each with its reason. */
  static final Map<String, String> WITHOUT_SEED = Map.of();

  private static ProductionPath path;

  @TempDir Path findings;

  @BeforeAll
  static void startPath() {
    path = ProductionPath.start();
  }

  @AfterAll
  static void closePath() {
    path.close();
  }

  @Test
  void everyAdmittedFormatHasASeed() {
    List<String> admitted = path.registry().supportedFormats().extensions();
    Set<String> seeded = new TreeSet<>();
    FuzzCorpus.load(admitted).forEach(seed -> seeded.add(seed.extension()));

    assertThat(admitted)
        .as("admitted formats without a seed under " + FuzzCorpus.CORPUS)
        .allSatisfy(
            extension ->
                assertThat(seeded.contains(extension) || WITHOUT_SEED.containsKey(extension))
                    .as(extension)
                    .isTrue());
    assertThat(WITHOUT_SEED.keySet())
        .as("WITHOUT_SEED for a seeded format")
        .doesNotContainAnyElementsOf(seeded);
  }

  @Test
  void everyGeneratedSeedIsCommittedAsTheGeneratorWritesIt() throws IOException {
    Path corpus = FuzzCorpus.repositoryRoot().resolve(FuzzCorpus.CORPUS);

    assertThat(FuzzCorpusGenerator.seeds())
        .as(
            "seeds missing or changed under "
                + FuzzCorpus.CORPUS
                + ": ./gradlew writeParserFuzzCorpus")
        .allSatisfy(
            (name, bytes) -> {
              assertThat(corpus.resolve(name)).exists();
              assertThat(corpus.resolve(name)).as(name).hasBinaryContent(bytes);
            });
  }

  @Test
  void theCorpusStaysSmall() throws IOException {
    try (Stream<Path> files = Files.walk(FuzzCorpus.repositoryRoot().resolve(FuzzCorpus.CORPUS))) {
      long bytes = files.filter(Files::isRegularFile).mapToLong(FuzzCorpusTest::sizeOf).sum();

      assertThat(bytes).isLessThanOrEqualTo(MAX_CORPUS_BYTES);
    }
  }

  @Test
  void everySeedPassesTheProductionPath() throws IOException {
    FuzzCampaign campaign =
        new FuzzCampaign(
            new FuzzCampaign.Settings(Duration.ZERO, 0, Duration.ofSeconds(60), findings));

    assertThat(campaign.run(path))
        .as("findings on the seeds as they are, under " + findings)
        .isEmpty();
  }

  private static long sizeOf(Path file) {
    try {
      return Files.size(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
