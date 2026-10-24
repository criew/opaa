package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.externalaccess.ExternalAccessDefaults;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The delivered MCP instructions gain the sentence that hit texts are document content: a row that
 * still carries the earlier delivered text takes the new one, a text the administration changed
 * stays, and a fresh row gets the new default.
 */
class McpInstructionsForeignContentMigrationTest extends AbstractBaselineTest {

  private static final String FILE =
      "db/changelog/external/2026-10-23-mcp-instructions-foreign-content.yaml";

  private static final String EARLIER_DEFAULT =
      "Bei Fragen zu Verwaltungsvorgängen dieser Behörde zuerst „search“ aufrufen. Antworten mit"
          + " Fundstellen belegen und die Herkunft angeben (Bibliothek, Dokument, Fundstelle)."
          + " Nichts erfinden, was die Treffer nicht hergeben.";

  @Override
  protected List<String> baseFixtureChangelogs() {
    return MasterChangelog.filesExcept(FILE);
  }

  @Test
  void theEarlierDeliveredTextTakesTheNewOne() throws Exception {
    assertThat(instructions()).isEqualTo(EARLIER_DEFAULT);

    applyChangelog(connection, FILE);

    assertThat(instructions()).isEqualTo(ExternalAccessDefaults.SERVER_INSTRUCTIONS);
  }

  @Test
  void aTextTheAdministrationChangedStays() throws Exception {
    execute(
        "UPDATE external_access_settings SET server_instructions = 'Nur Fristen nachschlagen.'");

    applyChangelog(connection, FILE);

    assertThat(instructions()).isEqualTo("Nur Fristen nachschlagen.");
  }

  @Test
  void aFreshRowGetsTheNewDefault() throws Exception {
    applyChangelog(connection, FILE);
    execute("DELETE FROM external_access_settings");

    execute("INSERT INTO external_access_settings (id) VALUES (1)");

    assertThat(instructions()).isEqualTo(ExternalAccessDefaults.SERVER_INSTRUCTIONS);
  }

  private String instructions() throws Exception {
    return stringOf("SELECT server_instructions FROM external_access_settings WHERE id = 1");
  }
}
