package io.opaa.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The nightly parser fuzzing ({@code ./gradlew parserFuzzTest}, not part of {@code test}): one
 * {@link FuzzCampaign} with the settings the task hands over as system properties - {@code
 * opaa.fuzz.duration} (ISO-8601, default one minute), {@code opaa.fuzz.seed} (default the clock),
 * {@code opaa.fuzz.inputTimeout} (default one minute) and {@code opaa.fuzz.findings}. With {@code
 * opaa.fuzz.replay}, a file or directory, it runs only those inputs, to reproduce a finding.
 */
class ParserFuzzTest {

  @Test
  void noInputBreaksTheProductionPath() throws IOException {
    FuzzCampaign.Settings settings = settings();
    System.out.println("parser fuzzing with campaign seed " + settings.seed());
    String replay = System.getProperty("opaa.fuzz.replay");
    FuzzCampaign campaign = new FuzzCampaign(settings);
    List<FuzzCampaign.Finding> findings =
        replay == null || replay.isBlank()
            ? campaign.run()
            : campaign.replay(files(Path.of(replay)));

    assertThat(findings)
        .as(
            "findings of the parser fuzzing, inputs and descriptions under "
                + settings.findings()
                + " (campaign seed "
                + settings.seed()
                + ")")
        .isEmpty();
  }

  private static FuzzCampaign.Settings settings() {
    return new FuzzCampaign.Settings(
        Duration.parse(System.getProperty("opaa.fuzz.duration", "PT1M")),
        Long.parseLong(
            System.getProperty("opaa.fuzz.seed", String.valueOf(System.currentTimeMillis()))),
        Duration.parse(System.getProperty("opaa.fuzz.inputTimeout", "PT1M")),
        Path.of(System.getProperty("opaa.fuzz.findings", "build/fuzz-findings")));
  }

  private static List<Path> files(Path replay) throws IOException {
    if (!Files.isDirectory(replay)) {
      return List.of(replay);
    }
    try (Stream<Path> files = Files.list(replay)) {
      return files
          .filter(file -> !file.toString().endsWith(".finding"))
          .filter(file -> !file.getFileName().toString().equals("summary.txt"))
          .sorted()
          .toList();
    }
  }
}
