package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delta test for {@code changes/020-document-type-suffixes.yaml} (#1263): the Kompositum endings of
 * the Dokumentart vocabulary and their exclusions are seeded, both are bound to an existing
 * vocabulary code, and a removed Dokumentart takes its endings with it.
 */
class Migration020DocumentTypeSuffixesTest extends AbstractMigrationTest {

  private static final String VOCABULARY_CHANGELOG_PATH =
      "db/changelog/changes/018-document-metadata-core-fields.yaml";
  private static final String CHANGELOG_PATH =
      "db/changelog/changes/020-document-type-suffixes.yaml";

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-baseline.yaml";
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    // 020 seeds rows referencing the vocabulary of 018, which the baseline fixture stops short of.
    applyChangelog(connection, VOCABULARY_CHANGELOG_PATH);
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void seedsTheEndingsAndTheirMinimumPrefixLengthPerDokumentart() throws Exception {
    assertThat(tableExists("document_type_suffixes")).isFalse();

    applyChangelog(connection, CHANGELOG_PATH);

    List<String> endings = new ArrayList<>();
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT code, suffix, min_prefix_length FROM document_type_suffixes"
                    + " ORDER BY code, suffix")) {
      while (rs.next()) {
        endings.add(
            rs.getString("code")
                + "="
                + rs.getString("suffix")
                + "/"
                + rs.getInt("min_prefix_length"));
      }
    }
    assertThat(endings)
        .containsExactly(
            "DIENSTANWEISUNG=dienstanweisung/3",
            "FORMULAR=formular/3",
            "GEBUEHRENVERZEICHNIS=gebuehrenverzeichnis/3",
            "PROTOKOLL=protokoll/3",
            "SATZUNG_ORDNUNG=ordnung/3",
            "SATZUNG_ORDNUNG=satzung/3",
            "VERMERK=vermerk/3");
  }

  @Test
  void seedsTheExcludedCompoundsOfTheOrdnungEnding() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    List<String> tokens = new ArrayList<>();
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT code, token FROM document_type_suffix_exclusions ORDER BY code, token")) {
      while (rs.next()) {
        tokens.add(rs.getString("code") + "=" + rs.getString("token"));
      }
    }
    assertThat(tokens)
        .containsExactly(
            "SATZUNG_ORDNUNG=anordnung",
            "SATZUNG_ORDNUNG=einordnung",
            "SATZUNG_ORDNUNG=neuordnung",
            "SATZUNG_ORDNUNG=unterordnung");
  }

  @Test
  void anEndingOutsideTheVocabularyIsNotStorableAndAMinimumBelowOneIsRejected() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    try (Statement statement = connection.createStatement()) {
      assertThatThrownBy(
              () ->
                  statement.execute(
                      "INSERT INTO document_type_suffixes (code, suffix) VALUES"
                          + " ('RUNDSCHREIBEN', 'rundschreiben')"))
          .isInstanceOf(SQLException.class);
    }
    try (Statement statement = connection.createStatement()) {
      assertThatThrownBy(
              () ->
                  statement.execute(
                      "INSERT INTO document_type_suffixes (code, suffix, min_prefix_length) VALUES"
                          + " ('VERMERK', 'merk', 0)"))
          .isInstanceOf(SQLException.class);
    }
    try (Statement statement = connection.createStatement()) {
      assertThatThrownBy(
              () ->
                  statement.execute(
                      "INSERT INTO document_type_suffix_exclusions (code, token) VALUES"
                          + " ('RUNDSCHREIBEN', 'rundschreiben')"))
          .isInstanceOf(SQLException.class);
    }
  }

  @Test
  void aRemovedDokumentartTakesItsEndingsAndExclusionsWithIt() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    try (Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM document_type_vocabulary WHERE code = 'SATZUNG_ORDNUNG'");
    }

    assertThat(count("document_type_suffixes WHERE code = 'SATZUNG_ORDNUNG'")).isZero();
    assertThat(count("document_type_suffix_exclusions WHERE code = 'SATZUNG_ORDNUNG'")).isZero();
    assertThat(count("document_type_suffixes")).isEqualTo(5);
  }

  private boolean tableExists(String table) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT to_regclass('public." + table + "') IS NOT NULL AS present")) {
      rs.next();
      return rs.getBoolean("present");
    }
  }

  private int count(String fromClause) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT count(*) FROM " + fromClause)) {
      rs.next();
      return rs.getInt(1);
    }
  }
}
