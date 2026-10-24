package io.opaa.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reading a version's manifest from area files, from the JSON form and from Git history. */
class ManifestSnapshotTest {

  @TempDir Path dir;

  @Test
  void theJsonFormAndTheAreaFilesOfOneManifestAreTheSameSnapshot() throws IOException {
    Path json = dir.resolve("faktenblaetter.json");
    Files.writeString(
        json,
        FactSheetRenderer.renderJson(
            ComplianceManifestLoader.load(),
            new FactSheetProvenance("1.2.3", "abc1234", LocalDate.EPOCH)));

    ManifestSnapshot fromJson = ManifestSnapshot.fromJson(json);
    ManifestSnapshot fromFiles =
        ManifestSnapshot.fromDirectory(Path.of("src/main/resources/compliance/manifest"), "x");

    assertThat(fromJson.origin().version()).isEqualTo("1.2.3");
    assertThat(fromJson.origin().commit()).isEqualTo("abc1234");
    assertThat(fromJson.sections()).containsKeys("data", "auditEvents", "settings", "dataFlows");
    assertThat(ManifestDelta.between(fromJson, fromFiles).changes()).isEmpty();
  }

  @Test
  void entriesCarryTheDefaultsOfTheirFileAndTheirArea() {
    ManifestSnapshot snapshot = ManifestDeltaTest.version(ManifestDeltaTest.BASE, "v1");

    assertThat(snapshot.sections().get("auditEvents").get("LIBRARY_CREATED"))
        .containsEntry("area", "test")
        .containsEntry("recordFields", List.of("Zeitpunkt", "handelnde Person"))
        .containsEntry("retention.rule", "Voreinstellung 36 Monate");
    assertThat(snapshot.sections().get("data").get("accounts"))
        .containsEntry("tables[users].personalColumns", List.of("subject", "email"))
        .doesNotContainKey("id");
    assertThat(snapshot.sections().get("nonPersonalTables").get("audit_retention_settings"))
        .containsEntry("columns", List.of("id", "retention_months"));
    assertThat(snapshot.areas()).containsEntry("test", "Testbereich");
  }

  /** The path to a person nests under its table; a changed exclusion is a change of its own. */
  @Test
  void thePathToAPersonIsReadPerTableAndAChangedExclusionShowsInTheDelta() {
    String older =
        ManifestDeltaTest.edit(
            ManifestDeltaTest.BASE,
            "        nonPersonalColumns: [id]\n",
            "        nonPersonalColumns: [id]\n"
                + "        person:\n"
                + "          columns: [subject]\n"
                + "          excludedColumns:\n"
                + "            email: Grund\n");
    String newer = ManifestDeltaTest.edit(older, "            email: Grund\n", "");

    assertThat(ManifestDeltaTest.version(older, "v1").sections().get("data").get("accounts"))
        .containsEntry("tables[users].person.columns", List.of("subject"))
        .containsEntry("tables[users].person.excludedColumns.email", "Grund");
    ManifestDelta.Change change =
        ManifestDeltaTest.delta(older, newer).changes().stream()
            .filter(c -> c.field() != null && c.field().contains("person"))
            .findFirst()
            .orElseThrow();
    assertThat(change.field()).isEqualTo("tables[users].person.excludedColumns.email");
    assertThat(change.assessment().undecided()).isTrue();
    assertThat(ManifestDeltaRenderer.label(change.field()))
        .isEqualTo("Tabelle `users`: Zuordnung zur Person, ausgenommene Spalte `email`");
  }

  @Test
  void aReferenceIsReadFromGitHistoryAndOneWithoutManifestIsEmpty() throws IOException {
    Path repository = GitFixture.repository(dir.resolve("repo"));

    ManifestSnapshot before = ManifestSnapshot.fromGit(repository, "v1");
    ManifestSnapshot after = ManifestSnapshot.fromGit(repository.resolve("backend"), "v2");

    assertThat(before.origin().hasManifest()).isFalse();
    assertThat(before.sections()).isEmpty();
    assertThat(after.origin().hasManifest()).isTrue();
    assertThat(after.origin().version()).isEqualTo("v2");
    assertThat(after.origin().commit()).matches("[0-9a-f]{40}");
    assertThat(after.sections().get("auditEvents")).containsKey("LIBRARY_CREATED");
  }

  @Test
  void aCommitOfTheJsonFormThatIsNoCommitIdIsUnknownAndTheFileIsNamedWithoutItsPath()
      throws IOException {
    Path json = dir.resolve("faktenblaetter.json");
    Files.writeString(
        json,
        """
        {"opaaVersion": "1.0.0", "commit": "--output=%s", "manifest": {"areas": {}}}
        """
            .formatted(dir.resolve("pwned")));

    ManifestSnapshot snapshot = ManifestSnapshot.fromJson(json);

    assertThat(snapshot.origin().commit()).isEqualTo(ManifestSnapshot.UNKNOWN);
    assertThat(snapshot.origin().source()).isEqualTo("faktenblaetter.json");
  }

  @Test
  void aValueThatLooksLikeAnOptionIsNeverReadAsOneByGit() throws IOException {
    Path repository = GitFixture.repository(dir.resolve("repo"));
    Path pwned = dir.resolve("pwned");

    assertThatThrownBy(() -> ManifestSnapshot.Git.subjects(repository, "--output=" + pwned, "v3"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> ManifestSnapshot.fromGit(repository, "--output=" + pwned))
        .isInstanceOf(IllegalArgumentException.class);
    try (var files = Files.list(dir)) {
      assertThat(files.map(file -> file.getFileName().toString()))
          .noneMatch(f -> f.startsWith("pwned"));
    }
  }

  /** A change backported unchanged to the older line is already installed there. */
  @Test
  void aCommitBackportedUnchangedToTheOlderSideIsNotListedAgain() throws IOException {
    Path repository = GitFixture.repository(dir.resolve("repo"));
    GitFixture.git(repository, "checkout", "-q", "-b", "release", "v2");
    GitFixture.git(repository, "cherry-pick", "-x", "v3");
    GitFixture.git(repository, "tag", "lts");
    GitFixture.git(repository, "checkout", "-q", "--detach", "v3");
    Files.writeString(repository.resolve("README.md"), "test\nmore\n");
    GitFixture.git(repository, "commit", "-q", "-a", "-m", "feat: Weiteres (#14)");
    GitFixture.git(repository, "tag", "v4");

    assertThat(ManifestSnapshot.Git.subjects(repository, "lts", "v4"))
        .containsExactly("feat: Weiteres (#14)");
    assertThat(ManifestSnapshot.Git.subjects(repository, "v1", "v3"))
        .containsExactly("feat(audit): Längere Frist (#13)", "feat(compliance): Manifest (#12)");
  }

  @Test
  void anUnknownReferenceFails() throws IOException {
    Path repository = GitFixture.repository(dir.resolve("repo"));

    assertThatThrownBy(() -> ManifestSnapshot.fromGit(repository, "v9"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("v9");
  }

  /**
   * A Git repository with tags {@code v1} (no manifest), {@code v2} (the fixture, commit naming
   * pull request 12) and {@code v3} (a longer retention, commit naming pull request 13).
   */
  static final class GitFixture {

    private GitFixture() {}

    static Path repository(Path root) throws IOException {
      Files.createDirectories(root);
      git(root, "init", "-q");
      Files.writeString(root.resolve("README.md"), "test\n");
      git(root, "add", "-A");
      git(root, "commit", "-q", "-m", "chore: start");
      git(root, "tag", "v1");
      Path manifest = root.resolve(ManifestSnapshot.REPOSITORY_PATH);
      Files.createDirectories(manifest);
      Files.writeString(manifest.resolve("test.yaml"), ManifestDeltaTest.BASE);
      git(root, "add", "-A");
      git(root, "commit", "-q", "-m", "feat(compliance): Manifest (#12)");
      git(root, "tag", "v2");
      Files.writeString(
          manifest.resolve("test.yaml"),
          ManifestDeltaTest.edit(
              ManifestDeltaTest.BASE, "defaultValue: \"36\"", "defaultValue: \"48\""));
      git(root, "commit", "-q", "-a", "-m", "feat(audit): Längere Frist (#13)");
      git(root, "tag", "v3");
      return root;
    }

    static void git(Path root, String... args) throws IOException {
      List<String> command =
          new ArrayList<>(
              List.of(
                  "git",
                  "-C",
                  root.toString(),
                  "-c",
                  "user.name=Test",
                  "-c",
                  "user.email=test@example.org",
                  "-c",
                  "commit.gpgsign=false",
                  "-c",
                  "tag.gpgsign=false",
                  "-c",
                  "core.hooksPath=/dev/null"));
      command.addAll(List.of(args));
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      try {
        if (process.waitFor() != 0) {
          throw new IllegalStateException(String.join(" ", command) + ": " + output);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
  }
}
