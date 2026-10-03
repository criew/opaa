package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The removal of the space visibility (#2156): {@code spaces.visibility} and its check go, applied
 * to an existing installation, i.e. the master without this file. Spaces of every former visibility
 * survive.
 */
class DropSpaceVisibilityMigrationTest extends AbstractBaselineTest {

  private static final String FILE = "db/changelog/workspace/2026-10-03-drop-space-visibility.yaml";

  @Override
  protected List<String> baseFixtureChangelogs() {
    return MasterChangelog.filesExcept(FILE);
  }

  @Test
  void theColumnAndItsCheckAreGoneAndSpacesOfEveryVisibilitySurvive() throws Exception {
    UUID owner = insertUser();
    List<UUID> spaces = List.of(insertSpace(owner), insertSpace(owner), insertSpace(owner));
    execute("UPDATE spaces SET visibility = 'DISCOVERABLE' WHERE id = '" + spaces.get(1) + "'");
    execute("UPDATE spaces SET visibility = 'OPEN' WHERE id = '" + spaces.get(2) + "'");

    applyChangelog(connection, FILE);

    assertThat(
            countWhere(
                "information_schema.columns",
                "table_schema = current_schema() AND table_name = 'spaces'"
                    + " AND column_name = 'visibility'"))
        .isZero();
    assertThat(countWhere("pg_constraint", "conname = 'chk_spaces_visibility'")).isZero();
    for (UUID space : spaces) {
      assertThat(countWhere("spaces", "id = '" + space + "'")).as("space %s", space).isEqualTo(1);
    }
  }
}
