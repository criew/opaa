package io.opaa.compliance;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.compliance.ComplianceManifest.DataEntry;
import io.opaa.compliance.ComplianceManifest.TableRef;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The path to a person ({@link ManifestCompleteness#personPathGaps}) against the fixture {@code
 * person-path/}: data entry {@code complete} is right, every other entry holds one gap.
 */
class ManifestPersonPathTest {

  private static final ComplianceManifest MANIFEST =
      ComplianceManifestLoader.load("classpath*:compliance-fixtures/person-path/*.yaml");

  private static final List<String> GAPS = ManifestCompleteness.personPathGaps(MANIFEST);

  private static final String FILE = "backend/src/main/resources/compliance/manifest/gaps.yaml";

  @Test
  void tablesWithColumnsViaOrExclusionAndADatumOutsideTheDatabaseWithReasonHaveNoGap() {
    assertThat(gapsOf("complete")).isEmpty();
    assertThat(gapsOf("outside")).isEmpty();
    assertThat(gapsOf("cross")).isEmpty();
  }

  @Test
  void aTableWithoutPathNamesFileAndTheYamlOfEachChoice() {
    assertThat(gapsOf("missing-path"))
        .containsExactly(
            "Table notes in "
                + FILE
                + ", data entry 'missing-path' has no path to a person. Add under table 'notes'"
                + " either the personal columns that identify the person\n"
                + "        person:\n"
                + "          columns: [<of author_id, text>]\n"
                + "  or, when a row reaches the person through another table, that table\n"
                + "        person:\n"
                + "          via: <a table of another data entry>\n"
                + "  (columns and via together state two paths) or, when the table is not part of"
                + " the self-disclosure,\n"
                + "        person:\n"
                + "          excluded: <reason, in German>\n"
                + "  With columns or via, a personal column left out of the self-disclosure goes"
                + " under\n"
                + "          excludedColumns:\n"
                + "            <column>: <reason, in German>");
  }

  @Test
  void aTableWithAPathAndAnExclusionKeepsOne() {
    assertThat(gapsOf("two-paths"))
        .singleElement()
        .asString()
        .startsWith(
            "Table drafts in "
                + FILE
                + ", data entry 'two-paths' states both a path to a person and an exclusion under"
                + " person; keep one.")
        .contains("          columns: [<of author_id>]");
  }

  @Test
  void aColumnOfThePathThatIsNoPersonalColumnIsNamedWithBothRemedies() {
    assertThat(gapsOf("unknown-column"))
        .containsExactly(
            "Column memos.id under person.columns in "
                + FILE
                + ", data entry 'unknown-column' is no personal column of the table. Name the"
                + " columns that identify the person:\n"
                + "          columns: [<of author_id>]\n"
                + "  or, if 'id' names a person, move it into\n"
                + "        personalColumns: [author_id, id]");
  }

  @Test
  void aViaToATableWithoutPathOfItsOwnIsAGap() {
    assertThat(gapsOf("dangling-via"))
        .containsExactly(
            "Table posts in "
                + FILE
                + ", data entry 'dangling-via' reaches the person via 'threads', which has no path"
                + " of its own. Give that table its own columns, or name a table that has them:\n"
                + "        person:\n"
                + "          via: <table with columns, of this or another data entry>");
  }

  /**
   * A personal column of a table with a path is part of the disclosure unless excluded with a
   * reason, so it cannot be left undecided; an exclusion naming no column of the table is a gap.
   */
  @Test
  void aPersonalColumnIsDisclosedUnlessExcludedAndAnExclusionMustNameAColumnOfTheTable() {
    assertThat(table("unlisted-column", "logs").disclosedColumns())
        .containsExactly("user_id", "phone");
    assertThat(gapsOf("unlisted-column"))
        .containsExactly(
            "Column logs.fax under person.excludedColumns in "
                + FILE
                + ", data entry 'unlisted-column' is no column of the table. Remove 'fax' there,"
                + " or rename it if the column was renamed.");
  }

  @Test
  void aDatumWithoutTableSaysWhyItIsNotPartOfTheSelfDisclosure() {
    assertThat(gapsOf("no-table"))
        .containsExactly(
            "Data entry 'no-table' in "
                + FILE
                + " has no table, so nothing in it reaches a person. Add to the entry why it is"
                + " not part of the self-disclosure:\n"
                + "    personExcluded: <reason, in German>");
  }

  @Test
  void theDisclosedColumnsFollowThePathAndItsExclusions() {
    assertThat(table("complete", "chats").disclosedColumns()).containsExactly("author_id", "title");
    assertThat(table("complete", "chat_messages").disclosedColumns()).containsExactly("content");
    assertThat(table("complete", "chat_reactions").disclosedColumns()).containsExactly("emoji");
    assertThat(table("complete", "chat_embeddings").disclosedColumns()).isEmpty();
    assertThat(table("cross", "attachments").disclosedColumns())
        .containsExactly("uploader_id", "file_name");
    assertThat(entry("complete").selfDisclosed()).isTrue();
    assertThat(entry("outside").selfDisclosed()).isFalse();
  }

  private static List<String> gapsOf(String id) {
    return GAPS.stream().filter(gap -> gap.contains("ata entry '" + id + "'")).toList();
  }

  private static DataEntry entry(String id) {
    return MANIFEST.data().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
  }

  private static TableRef table(String id, String name) {
    return entry(id).tables().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
  }
}
