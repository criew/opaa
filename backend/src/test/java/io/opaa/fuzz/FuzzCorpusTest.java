package io.opaa.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.apache.poi.util.LocaleUtil;
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

  /**
   * Compares by {@link SeedComparison}, not byte for byte: another zlib compresses the same content
   * to other bytes, and the committed file stays valid for it.
   */
  @Test
  void everyGeneratedSeedIsCommittedAsTheGeneratorWritesIt() throws IOException {
    Path corpus = FuzzCorpus.repositoryRoot().resolve(FuzzCorpus.CORPUS);
    List<String> differences = new ArrayList<>();
    for (Map.Entry<String, byte[]> seed : FuzzCorpusGenerator.seeds().entrySet()) {
      Path committed = corpus.resolve(seed.getKey());
      if (!Files.exists(committed)) {
        differences.add(seed.getKey() + ": not committed");
        continue;
      }
      SeedComparison.difference(seed.getKey(), Files.readAllBytes(committed), seed.getValue())
          .ifPresent(differences::add);
    }

    assertThat(differences)
        .as(
            "seeds missing or changed under "
                + FuzzCorpus.CORPUS
                + " (committed vs. generated): ./gradlew writeParserFuzzCorpus")
        .isEmpty();
  }

  /** POI writes its user locale into new documents, and other tests in the same JVM may set it. */
  @Test
  void theGeneratedSeedsDoNotDependOnThePoiUserLocale() throws IOException {
    Map<String, byte[]> usual = FuzzCorpusGenerator.seeds();
    Map<String, byte[]> german;
    LocaleUtil.setUserLocale(Locale.GERMANY);
    try {
      german = FuzzCorpusGenerator.seeds();
    } finally {
      LocaleUtil.resetUserLocale();
    }

    assertThat(german.keySet()).isEqualTo(usual.keySet());
    for (Map.Entry<String, byte[]> seed : usual.entrySet()) {
      assertThat(
              SeedComparison.difference(seed.getKey(), seed.getValue(), german.get(seed.getKey())))
          .as(seed.getKey())
          .isEmpty();
    }
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
