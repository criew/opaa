package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The instruction of a space to the model: {@code spaces.model_instruction}, applied to an existing
 * installation. Every existing space gets none, a text of at most 1000 characters is stored, and an
 * empty or blank text is rejected - "no instruction" is {@code null}, never an empty string.
 */
class SpaceModelInstructionMigrationTest extends AbstractBaselineTest {

  private static final String FILE =
      "db/changelog/workspace/2026-10-11-space-model-instruction.yaml";

  @Override
  protected List<String> baseFixtureChangelogs() {
    return MasterChangelog.filesExcept(FILE);
  }

  @Test
  void existingSpacesSurviveWithoutAnInstruction() throws Exception {
    UUID owner = insertUser();
    List<UUID> spaces = List.of(insertSpace(owner), insertSpace(owner));

    applyChangelog(connection, FILE);

    for (UUID space : spaces) {
      assertThat(countWhere("spaces", "id = " + quoted(space) + " AND model_instruction IS NULL"))
          .as("space %s", space)
          .isEqualTo(1);
    }
  }

  @Test
  void aTextOfAtMostOneThousandCharactersIsStored() throws Exception {
    applyChangelog(connection, FILE);
    UUID space = insertSpace(insertUser());
    String text = "x".repeat(1000);

    execute(
        "UPDATE spaces SET model_instruction = " + quoted(text) + " WHERE id = " + quoted(space));

    assertThat(stringOf("SELECT model_instruction FROM spaces WHERE id = " + quoted(space)))
        .isEqualTo(text);
  }

  @Test
  void aLongerTextIsRejected() throws Exception {
    applyChangelog(connection, FILE);
    UUID space = insertSpace(insertUser());

    assertRejected(
        "UPDATE spaces SET model_instruction = "
            + quoted("x".repeat(1001))
            + " WHERE id = "
            + quoted(space),
        "value too long");
  }

  @Test
  void anEmptyOrBlankTextIsRejected() throws Exception {
    applyChangelog(connection, FILE);
    UUID space = insertSpace(insertUser());

    assertRejected(
        "UPDATE spaces SET model_instruction = '' WHERE id = " + quoted(space),
        "chk_spaces_model_instruction_not_blank");
    assertRejected(
        "UPDATE spaces SET model_instruction = E' \\n\\t ' WHERE id = " + quoted(space),
        "chk_spaces_model_instruction_not_blank");
  }
}
