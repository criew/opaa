package io.opaa.migration;

import static io.opaa.migration.AssetShellMigrationFixtures.DEFAULT_ORGANIZATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delta tests for {@code changes/085-create-prompt-libraries.yaml}: a prompt library is a type row
 * next to a shell row of its own type, a prompt belongs to exactly one library of the same
 * organization, and deleting the shell takes both with it.
 */
class Migration085PromptLibrariesTest extends AbstractMigrationTest {

  static final String FIXTURE_CHAIN = "db/changelog/test-master-through-084.yaml";
  static final String PROMPT_LIBRARIES = "db/changelog/changes/085-create-prompt-libraries.yaml";

  private Connection connection;
  private AssetShellMigrationFixtures fixtures;
  private UUID owner;

  @Override
  protected String baseFixtureChangelogPath() {
    return FIXTURE_CHAIN;
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    connection.setAutoCommit(true);
    fixtures = new AssetShellMigrationFixtures(connection);
    owner = fixtures.user();
    applyChangelog(connection, PROMPT_LIBRARIES);
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void aTypeRowStandsOnlyNextToAShellRowOfItsOwnType() throws SQLException {
    UUID library = shell("KNOWLEDGE_LIBRARY");

    assertThatThrownBy(() -> typeRow(library))
        .as("a knowledge library's shell cannot carry a prompt library")
        .hasMessageContaining("fk_prompt_libraries_asset");
    assertThatThrownBy(() -> typeRow(UUID.randomUUID()))
        .hasMessageContaining("fk_prompt_libraries_asset");
    assertThatCode(() -> typeRow(shell("PROMPT_LIBRARY"))).doesNotThrowAnyException();
  }

  @Test
  void aPromptNameIsUniqueWithinItsLibraryAndShapedLikeASlashCommand() throws SQLException {
    UUID first = library();
    UUID second = library();
    prompt(first, "anhoerung", "Text");

    assertThatThrownBy(() -> prompt(first, "anhoerung", "Text"))
        .hasMessageContaining("uk_prompts_library_name");
    assertThatCode(() -> prompt(second, "anhoerung", "Text")).doesNotThrowAnyException();
    assertThatCode(() -> prompt(first, "vermerk-2", "Text")).doesNotThrowAnyException();
    for (String invalid : new String[] {"Anhoerung", "an hoerung", "-a", "a-", "a--b", "a_b", ""}) {
      assertThatThrownBy(() -> prompt(first, invalid, "Text"))
          .as(invalid)
          .hasMessageContaining("chk_prompts_name_format");
    }
  }

  @Test
  void theTextHoldsAtMost8000CharactersAndTheVariablesAreAnArray() throws SQLException {
    UUID library = library();

    assertThatCode(() -> prompt(library, "lang", "x".repeat(8000))).doesNotThrowAnyException();
    assertThatThrownBy(() -> prompt(library, "zu-lang", "x".repeat(8001)))
        .hasMessageContaining("chk_prompts_text_length");
    assertThatThrownBy(() -> prompt(library, "leer", ""))
        .hasMessageContaining("chk_prompts_text_length");
    assertThatThrownBy(
            () ->
                fixtures.execute(
                    "INSERT INTO prompts (id, library_id, organization_id, name, title, text,"
                        + " variables) VALUES (?, ?, ?, 'objekt', 'Titel', 'Text', '{}'::jsonb)",
                    UUID.randomUUID(),
                    library,
                    DEFAULT_ORGANIZATION))
        .hasMessageContaining("chk_prompts_variables_array");
    assertThat(
            fixtures.string(
                "SELECT variables::text FROM prompts WHERE library_id = ? AND name = 'lang'",
                library))
        .isEqualTo("[]");
  }

  @Test
  void aPromptBelongsToALibraryOfItsOwnOrganization() throws SQLException {
    UUID library = library();

    assertThatThrownBy(
            () ->
                fixtures.execute(
                    "INSERT INTO prompts (id, library_id, organization_id, name, title, text)"
                        + " VALUES (?, ?, ?, 'fremd', 'Titel', 'Text')",
                    UUID.randomUUID(),
                    library,
                    UUID.randomUUID()))
        .hasMessageContaining("fk_prompts_library_organization");
  }

  @Test
  void deletingTheShellTakesTheLibraryAndItsPromptsWithIt() throws SQLException {
    UUID library = library();
    prompt(library, "anhoerung", "Text");

    fixtures.execute("DELETE FROM assets WHERE id = ?", library);

    assertThat(fixtures.count("SELECT count(*) FROM prompt_libraries WHERE id = ?", library))
        .isZero();
    assertThat(fixtures.count("SELECT count(*) FROM prompts WHERE library_id = ?", library))
        .isZero();
  }

  @Test
  void theChangelogIsReferencedByTheMasterChangelog() throws Exception {
    assertThat(AssetShellMigrationFixtures.masterChangelog()).contains(PROMPT_LIBRARIES);
  }

  private UUID library() throws SQLException {
    UUID library = shell("PROMPT_LIBRARY");
    typeRow(library);
    return library;
  }

  private UUID shell(String assetType) throws SQLException {
    UUID asset = UUID.randomUUID();
    fixtures.execute(
        "INSERT INTO assets (id, asset_type, organization_id, name, owner_type, owner_user_id,"
            + " visibility) VALUES (?, ?, ?, 'Prompts', 'USER', ?, 'PRIVATE')",
        asset,
        assetType,
        DEFAULT_ORGANIZATION,
        owner);
    return asset;
  }

  private void typeRow(UUID library) throws SQLException {
    fixtures.execute(
        "INSERT INTO prompt_libraries (id, organization_id) VALUES (?, ?)",
        library,
        DEFAULT_ORGANIZATION);
  }

  private void prompt(UUID library, String name, String text) throws SQLException {
    fixtures.execute(
        "INSERT INTO prompts (id, library_id, organization_id, name, title, text) VALUES (?, ?,"
            + " ?, ?, 'Titel', ?)",
        UUID.randomUUID(),
        library,
        DEFAULT_ORGANIZATION,
        name,
        text);
  }
}
