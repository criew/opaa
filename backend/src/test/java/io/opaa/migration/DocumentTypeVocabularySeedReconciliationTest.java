package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.metadata.DocumentTypeVocabularyEntry;
import io.opaa.indexing.metadata.TestVocabularies;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Reconciles {@link TestVocabularies} - the database-free snapshot the unit tests and the Eval
 * corpus assertions run against - with the Dokumentart vocabulary the full changelog actually seeds
 * (#1325). Every code, label, sort order, synonym, Kompositum ending and exclusion must match; a
 * seed value added, removed or changed in any changeset fails here instead of drifting silently
 * past the snapshot.
 */
class DocumentTypeVocabularySeedReconciliationTest extends AbstractMigrationTest {

  private static final String MASTER_CHANGELOG_PATH = "db/changelog/db.changelog-master.yaml";

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-baseline.yaml";
  }

  @BeforeEach
  void setUp() throws SQLException {
    connection = connect();
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void testVocabulariesMatchesTheSeededVocabularyOfTheFullChangelog() throws Exception {
    // The whole master, not only 018/020: a later changeset extending or correcting the seed must
    // reach this comparison too.
    applyChangelog(connection, MASTER_CHANGELOG_PATH);

    // Compared in order, unsorted on purpose: TestVocabularies promises to list the entries in
    // sort_order, and a snapshot listing them in a different one would mismatch the repository.
    assertThat(readSeededVocabulary())
        .containsExactlyElementsOf(
            TestVocabularies.deliveredEntries().stream()
                .map(DocumentTypeVocabularySeedReconciliationTest::render)
                .toList());
  }

  /**
   * One rendered line per Dokumentart, in {@code sort_order}, so a diff names the value at fault.
   */
  private List<String> readSeededVocabulary() throws SQLException {
    List<String> rendered = new ArrayList<>();
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT code, label, sort_order FROM document_type_vocabulary ORDER BY sort_order, code")) {
      while (rs.next()) {
        String code = rs.getString("code");
        rendered.add(
            render(
                code,
                rs.getString("label"),
                rs.getInt("sort_order"),
                readStrings("SELECT synonym FROM document_type_synonyms WHERE code = ?", code),
                readSuffixes(code),
                readStrings(
                    "SELECT token FROM document_type_suffix_exclusions WHERE code = ?", code)));
      }
    }
    return rendered;
  }

  private Set<String> readStrings(String sql, String code) throws SQLException {
    Set<String> values = new LinkedHashSet<>();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, code);
      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) {
          values.add(rs.getString(1));
        }
      }
    }
    return values;
  }

  private Set<String> readSuffixes(String code) throws SQLException {
    Set<String> values = new LinkedHashSet<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT suffix, min_prefix_length FROM document_type_suffixes WHERE code = ?")) {
      statement.setString(1, code);
      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) {
          values.add(rs.getString("suffix") + "/" + rs.getInt("min_prefix_length"));
        }
      }
    }
    return values;
  }

  private static String render(DocumentTypeVocabularyEntry entry) {
    return render(
        entry.getCode(),
        entry.getLabel(),
        entry.getSortOrder(),
        entry.getSynonyms(),
        entry.getSuffixes().stream()
            .map(suffix -> suffix.getSuffix() + "/" + suffix.getMinPrefixLength())
            .collect(Collectors.toSet()),
        entry.getSuffixExclusions());
  }

  private static String render(
      String code,
      String label,
      int sortOrder,
      Set<String> synonyms,
      Set<String> suffixes,
      Set<String> suffixExclusions) {
    return code
        + " | "
        + label
        + " | "
        + sortOrder
        + " | synonyms="
        + sorted(synonyms)
        + " | suffixes="
        + sorted(suffixes)
        + " | exclusions="
        + sorted(suffixExclusions);
  }

  private static List<String> sorted(Set<String> values) {
    return values.stream().sorted().toList();
  }
}
