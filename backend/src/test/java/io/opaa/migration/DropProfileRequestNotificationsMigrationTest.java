package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Notifications of the withdrawn connection profile requests go, since their types can no longer be
 * read; every other notification of the same person stays. Applied to an existing installation,
 * i.e. the master without this file.
 */
class DropProfileRequestNotificationsMigrationTest extends AbstractBaselineTest {

  private static final String FILE =
      "db/changelog/identity/2026-10-11-drop-profile-request-notifications.yaml";

  @Override
  protected List<String> baseFixtureChangelogs() {
    return MasterChangelog.filesExcept(FILE);
  }

  @Test
  void onlyTheNotificationsOfTheWithdrawnTypesGo() throws Exception {
    UUID person = insertUser();
    UUID requested = insertNotification(person, "CONNECTION_PROFILE_REQUESTED");
    UUID resolved = insertNotification(person, "CONNECTION_PROFILE_REQUEST_RESOLVED");
    UUID kept = insertNotification(person, "CONNECTION_ENDED");

    applyChangelog(connection, FILE);

    assertThat(
            countWhere(
                "notifications", "id IN (" + quoted(requested) + ", " + quoted(resolved) + ")"))
        .isZero();
    assertThat(countWhere("notifications", "id = " + quoted(kept))).isEqualTo(1);
  }

  private UUID insertNotification(UUID recipient, String type) throws Exception {
    UUID id = UUID.randomUUID();
    execute(
        "INSERT INTO notifications (id, organization_id, recipient_user_id, type, title)"
            + " VALUES ("
            + quoted(id)
            + ", (SELECT organization_id FROM users WHERE id = "
            + quoted(recipient)
            + "), "
            + quoted(recipient)
            + ", '"
            + type
            + "', 'Titel')");
    return id;
  }
}
