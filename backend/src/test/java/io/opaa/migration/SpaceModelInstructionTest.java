package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The instruction of a space to the model, {@code spaces.model_instruction}: a new space has none,
 * a text of at most 1000 characters is stored, and an empty or blank text is rejected - "no
 * instruction" is {@code null}, never an empty string.
 */
class SpaceModelInstructionTest extends AbstractBaselineTest {

  @Test
  void aNewSpaceHasNoInstruction() throws Exception {
    UUID space = insertSpace(insertUser());

    assertThat(countWhere("spaces", "id = " + quoted(space) + " AND model_instruction IS NULL"))
        .isOne();
  }

  @Test
  void aTextOfAtMostOneThousandCharactersIsStored() throws Exception {
    UUID space = insertSpace(insertUser());
    String text = "x".repeat(1000);

    execute(
        "UPDATE spaces SET model_instruction = " + quoted(text) + " WHERE id = " + quoted(space));

    assertThat(stringOf("SELECT model_instruction FROM spaces WHERE id = " + quoted(space)))
        .isEqualTo(text);
  }

  @Test
  void aLongerTextIsRejected() throws Exception {
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
    UUID space = insertSpace(insertUser());

    assertRejected(
        "UPDATE spaces SET model_instruction = '' WHERE id = " + quoted(space),
        "chk_spaces_model_instruction_not_blank");
    assertRejected(
        "UPDATE spaces SET model_instruction = E' \\n\\t ' WHERE id = " + quoted(space),
        "chk_spaces_model_instruction_not_blank");
  }
}
