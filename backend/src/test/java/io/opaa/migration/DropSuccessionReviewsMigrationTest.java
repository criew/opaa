package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The removal of the Sichtungsvermerk: the table {@code succession_reviews} goes, its stored notes
 * with it, while the cases they were written against stay. Applied to an existing installation,
 * i.e. the master without this file.
 */
class DropSuccessionReviewsMigrationTest extends AbstractBaselineTest {

  private static final String FILE = "db/changelog/rights/2026-10-08-drop-succession-reviews.yaml";

  @Override
  protected List<String> baseFixtureChangelogs() {
    return MasterChangelog.filesExcept(FILE);
  }

  @Test
  void theTableIsGoneAndTheCasesStay() throws Exception {
    UUID openCase = UUID.randomUUID();
    execute(
        "INSERT INTO succession_cases (id, organization_id, kind, object_type, asset_type,"
            + " object_id, first_seen_at, last_seen_at) VALUES ('"
            + openCase
            + "', '"
            + SEEDED_ORGANIZATION_ID
            + "', 'OPEN_SUCCESSION', 'SPACE', NULL, '"
            + UUID.randomUUID()
            + "', now(), now())");
    execute(
        "INSERT INTO succession_reviews (id, case_id, organization_id, reviewed_at,"
            + " reviewed_by_user_id, reason) VALUES (gen_random_uuid(), '"
            + openCase
            + "', '"
            + SEEDED_ORGANIZATION_ID
            + "', now(), NULL, 'weiterhin offen')");

    applyChangelog(connection, FILE);

    assertThat(
            countWhere(
                "information_schema.tables",
                "table_schema = current_schema() AND table_name = 'succession_reviews'"))
        .isZero();
    assertThat(countWhere("succession_cases", "id = '" + openCase + "'")).isEqualTo(1);
  }
}
