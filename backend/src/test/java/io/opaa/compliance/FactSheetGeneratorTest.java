package io.opaa.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

  @Test
  void aGivenGenerationDateMakesTheSheetsRepeatable() throws IOException {
    Path first = out.resolve("erster");
    Path second = out.resolve("zweiter");

    FactSheetGenerator.main(new String[] {first.toString(), "1.2.3", "abc1234", "2026-10-01"});
    FactSheetGenerator.main(new String[] {second.toString(), "1.2.3", "abc1234", "2026-10-01"});

    assertThat(Files.readString(first.resolve("fristen.md"))).contains("erzeugt-am: 2026-10-01\n");
    assertThat(Files.readString(first.resolve("faktenblaetter.json"))).contains("\"2026-10-01\"");
    try (Stream<Path> files = Files.list(first)) {
      for (Path file : files.toList()) {
        assertThat(file).hasSameBinaryContentAs(second.resolve(file.getFileName()));
      }
    }
  }

  @Test
  void aGenerationDateThatIsNoCalendarDayIsRefused() {
    String target = out.resolve("faktenblaetter").toString();

    assertThatThrownBy(
            () -> FactSheetGenerator.main(new String[] {target, "1.2.3", "abc1234", "2026-02-30"}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("2026-02-30");
  }
}
