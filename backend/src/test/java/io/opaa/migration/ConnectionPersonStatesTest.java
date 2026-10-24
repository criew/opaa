package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The lifecycle state of a person for the connections: it goes with the account and is never
 * deactivated and resting at once.
 */
class ConnectionPersonStatesTest extends AbstractBaselineTest {

  @Test
  void aPersonsStateGoesWithTheAccountAndHoldsOneStateAtMost() throws Exception {
    UUID person = insertUser();
    UUID leaving = insertUser();
    execute(state(leaving, "now()", "NULL"));
    assertRejected(state(person, "now()", "now()"), "chk_connection_person_states_one_state");
    execute(state(person, "NULL", "now()"));

    execute("DELETE FROM users WHERE id = '" + leaving + "'");

    assertThat(countWhere("connection_person_states", "user_id = '" + leaving + "'")).isZero();
    assertThat(countWhere("connection_person_states", "user_id = '" + person + "'")).isEqualTo(1);
  }

  private static String state(UUID user, String deactivatedSince, String dormantSince) {
    return "INSERT INTO connection_person_states (user_id, deactivated_since, dormant_since,"
        + " checked_at) VALUES ('"
        + user
        + "', "
        + deactivatedSince
        + ", "
        + dormantSince
        + ", now())";
  }
}
