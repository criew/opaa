package io.opaa.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.compliance.ManifestSnapshotTest.GitFixture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The entry point of {@code ./gradlew complianceDelta}: both forms, offline, deterministic. */
class ManifestDeltaGeneratorTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @TempDir Path dir;

  @Test
  void writesBothFormsForTwoTagsWithTheLabelledPullRequestsBetweenThem() throws IOException {
    Path repository = GitFixture.repository(dir.resolve("repo"));
    Path prs = dir.resolve("prs.json");
    Files.writeString(
        prs,
        """
        [{"number": 13, "title": "Längere Frist", "url": "https://example.org/13"},
         {"number": 99, "title": "Anderswo", "url": "https://example.org/99"},
         {"number": 12, "title": "Manifest", "url": "https://example.org/12"}]
        """);
    Path out = dir.resolve("delta");

    run(
        "--out",
        out.toString(),
        "--repo",
        repository.toString(),
        "--from-ref",
        "v2",
        "--to-ref",
        "v3",
        "--labelled-prs",
        prs.toString());

    JsonNode json = JSON.readTree(Files.readString(out.resolve(ManifestDeltaGenerator.JSON_FILE)));
    assertThat(json.path("from").path("version").asString()).isEqualTo("v2");
    assertThat(json.path("changes")).hasSize(1);
    assertThat(json.path("changes").get(0).path("assessment").path("stage").asString())
        .isEqualTo("ZUSTIMMUNG");
    JsonNode labelled = json.path("labelledPullRequests");
    assertThat(labelled.path("filteredByRange").asBoolean()).isTrue();
    assertThat(labelled.path("pullRequests")).hasSize(1);
    assertThat(labelled.path("pullRequests").get(0).path("number").asInt()).isEqualTo(13);
    assertThat(Files.readString(out.resolve(ManifestDeltaGenerator.MARKDOWN_FILE)))
        .contains("bis: v3\n")
        .contains("- [#13 Längere Frist](https://example.org/13)")
        .doesNotContain("#99")
        .doesNotContain("#12 ");
  }

  @Test
  void aVersionWithoutManifestMakesEveryEntryNewAndSaysSo() throws IOException {
    Path repository = GitFixture.repository(dir.resolve("repo"));
    Path out = dir.resolve("delta");

    run(
        "--out",
        out.toString(),
        "--repo",
        repository.toString(),
        "--from-ref",
        "v1",
        "--to-ref",
        "v3");

    String markdown = Files.readString(out.resolve(ManifestDeltaGenerator.MARKDOWN_FILE));
    assertThat(markdown).contains("OPAA v1 hat noch kein Fakten-Manifest");
    assertThat(markdown).contains("Nicht abgefragt");
    assertThat(markdown).doesNotContain("Entfällt");
  }

  @Test
  void twoRunsOnTheSameVersionsWriteTheSameFiles() throws IOException {
    Path repository = GitFixture.repository(dir.resolve("repo"));
    Path first = dir.resolve("first");
    Path second = dir.resolve("second");

    run(
        "--out",
        first.toString(),
        "--repo",
        repository.toString(),
        "--from-ref",
        "v1",
        "--to-ref",
        "v3");
    run(
        "--out",
        second.toString(),
        "--repo",
        repository.toString(),
        "--from-ref",
        "v1",
        "--to-ref",
        "v3");

    for (String file :
        new String[] {ManifestDeltaGenerator.MARKDOWN_FILE, ManifestDeltaGenerator.JSON_FILE}) {
      assertThat(Files.readString(second.resolve(file)))
          .isEqualTo(Files.readString(first.resolve(file)));
    }
  }

  @Test
  void comparesTheJsonFormOfOneVersionWithTheAreaFilesOfAnother() throws IOException {
    Path json = dir.resolve("faktenblaetter.json");
    Files.writeString(
        json,
        FactSheetRenderer.renderJson(
            ComplianceManifestLoader.load(),
            new FactSheetProvenance("1.2.3", "abc1234", java.time.LocalDate.EPOCH)));
    Path out = dir.resolve("delta");

    run(
        "--out",
        out.toString(),
        "--from-json",
        json.toString(),
        "--to-dir",
        "src/main/resources/compliance/manifest");

    JsonNode result =
        JSON.readTree(Files.readString(out.resolve(ManifestDeltaGenerator.JSON_FILE)));
    assertThat(result.path("from").path("version").asString()).isEqualTo("1.2.3");
    assertThat(result.path("to").path("version").asString()).isEqualTo("Arbeitsstand");
    assertThat(result.path("changes")).isEmpty();
  }

  @Test
  void aForgedCommitInTheJsonFormNeitherReachesGitNorNarrowsTheList() throws IOException {
    Path repository = GitFixture.repository(dir.resolve("repo"));
    Path pwned = dir.resolve("pwned");
    Path json = dir.resolve("faktenblaetter.json");
    Files.writeString(
        json,
        """
        {"opaaVersion": "1.0.0", "commit": "--output=%s", "manifest": {"areas": {}}}
        """
            .formatted(pwned));
    Path prs = dir.resolve("prs.json");
    Files.writeString(prs, "[{\"number\": 12, \"title\": \"Manifest\", \"url\": \"\"}]");
    Path out = dir.resolve("delta");

    run(
        "--out",
        out.toString(),
        "--repo",
        repository.toString(),
        "--from-json",
        json.toString(),
        "--to-ref",
        "v3",
        "--labelled-prs",
        prs.toString());

    try (var files = Files.list(dir)) {
      assertThat(files.map(file -> file.getFileName().toString()))
          .noneMatch(f -> f.startsWith("pwned"));
    }
    JsonNode result =
        JSON.readTree(Files.readString(out.resolve(ManifestDeltaGenerator.JSON_FILE)));
    assertThat(result.path("from").path("commit").asString()).isEqualTo("unbekannt");
    assertThat(result.path("labelledPullRequests").path("filteredByRange").asBoolean()).isFalse();
  }

  @Test
  void needsExactlyOneSourcePerSide() {
    assertThatThrownBy(() -> run("--out", dir.toString(), "--to-ref", "v1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--from-ref");
    assertThatThrownBy(
            () ->
                run(
                    "--out",
                    dir.toString(),
                    "--from-ref",
                    "v1",
                    "--from-json",
                    "x",
                    "--to-ref",
                    "v2"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static void run(String... args) {
    ManifestDeltaGenerator.main(args);
  }
}
