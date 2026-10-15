package io.opaa.compliance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The entry point of {@code ./gradlew complianceDocs}: six sheets and the JSON form per run. */
class FactSheetGeneratorTest {

  @TempDir Path out;

  @Test
  void writesTheSixSheetsAndTheJsonForm() throws IOException {
    Path target = out.resolve("faktenblaetter");

    FactSheetGenerator.main(new String[] {target.toString(), "1.2.3", "abc1234"});

    try (Stream<Path> files = Files.list(target)) {
      assertThat(files.map(file -> file.getFileName().toString()))
          .containsExactlyInAnyOrder(
              "datenkatalog.md",
              "protokollereignisse.md",
              "rollensicht.md",
              "datenfluesse.md",
              "voreinstellungen.md",
              "fristen.md",
              "faktenblaetter.json");
    }
    assertThat(Files.readString(target.resolve("fristen.md")))
        .contains("gilt-fuer: 1.2.3\ncommit: abc1234\n");
    assertThat(Files.readString(target.resolve("faktenblaetter.json"))).contains("\"abc1234\"");
  }

  @Test
  void replacesTheSheetsOfAnEarlierRun() throws IOException {
    Path target = out.resolve("faktenblaetter");
    Files.createDirectories(target);
    Files.writeString(target.resolve("veraltet.md"), "alt");

    FactSheetGenerator.main(new String[] {target.toString(), "1.2.3", "abc1234"});

    assertThat(target.resolve("veraltet.md")).doesNotExist();
  }
}
