package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The owner kinds of the connection log applied to an installation that already has entries: the
 * existing ones become a person's, every writer then names the owner, and {@code
 * chk_connection_log_owner} keeps a person's entry free of library and account name.
 */
class ConnectionLogOwnerMigrationTest extends AbstractBaselineTest {

  private static final String FILE = "db/changelog/connections/2026-10-04-log-owner.yaml";

  /** Quoted as Postgres names it, so no other constraint's name can match. */
  private static final String CHECK = "\"chk_connection_log_owner\"";

  private static final String CAUSE_CHECK = "\"chk_connection_log_cause\"";

  private final UUID existing = UUID.randomUUID();

  @Override
  protected List<String> baseFixtureChangelogs() {
    return MasterChangelog.filesExcept(FILE);
  }

  @BeforeEach
  void seedAnEntryAndApply() throws Exception {
    execute(
        "INSERT INTO connection_log (event_id, organization_id, recorded_at, event_type,"
            + " actor_ref, person_ref, profile_id, profile_name) VALUES ('"
            + existing
            + "', '"
            + SEEDED_ORGANIZATION_ID
            + "', now(), 'CONNECTED', 'actor', 'person', gen_random_uuid(), 'Nextcloud')");
    applyChangelog(connection, FILE);
    connection.setAutoCommit(true);
  }

  @Test
  void anExistingEntryIsAPersonsAndNewOnesMustNameTheirOwner() throws Exception {
    assertThat(
            stringOf("SELECT owner_kind FROM connection_log WHERE event_id = '" + existing + "'"))
        .isEqualTo("PERSON");
    assertThat(
            longOf(
                "SELECT count(*) FROM information_schema.columns WHERE table_schema ="
                    + " current_schema() AND table_name = 'connection_log' AND column_name ="
                    + " 'owner_kind' AND column_default IS NULL AND is_nullable = 'NO'"))
        .as("no default after the backfill: every writer names the owner")
        .isEqualTo(1);
    assertRejected(insert("NULL", "'person'", "NULL", "NULL"), "owner_kind");
  }

  @Test
  void acceptsEachOwnerKindInItsShape() {
    assertThatCode(() -> execute(insert("'PERSON'", "'person'", "NULL", "NULL")))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                execute(insert("'LIBRARY'", "NULL", "gen_random_uuid()", "'svc-opaa@rathaus.de'")))
        .doesNotThrowAnyException();
    assertThatCode(() -> execute(insert("'LIBRARY'", "NULL", "gen_random_uuid()", "NULL")))
        .doesNotThrowAnyException();
    assertThatCode(() -> execute(insert("'PROFILE'", "NULL", "NULL", "NULL")))
        .doesNotThrowAnyException();
  }

  /** A person's entry names no library and no account - a guarantee of the database. */
  @Test
  void aPersonsEntryNeverCarriesALibraryOrAnAccount() {
    assertRejected(insert("'PERSON'", "NULL", "NULL", "NULL"), CHECK);
    assertRejected(insert("'PERSON'", "'person'", "gen_random_uuid()", "NULL"), CHECK);
    assertRejected(insert("'PERSON'", "'person'", "NULL", "'avogt'"), CHECK);
  }

  @Test
  void aLibrarysEntryNamesItsLibraryAndNoPerson() {
    assertRejected(insert("'LIBRARY'", "NULL", "NULL", "'svc'"), CHECK);
    assertRejected(insert("'LIBRARY'", "'person'", "gen_random_uuid()", "NULL"), CHECK);
  }

  @Test
  void aProfilesEntryNamesNeitherPersonNorLibraryNorAccount() {
    assertRejected(insert("'PROFILE'", "'person'", "NULL", "NULL"), CHECK);
    assertRejected(insert("'PROFILE'", "NULL", "gen_random_uuid()", "NULL"), CHECK);
    assertRejected(insert("'PROFILE'", "NULL", "NULL", "'svc'"), CHECK);
  }

  /** A library's connection also ends with its library or with a move to another profile. */
  @Test
  void acceptsTheEndCausesOfALibrarysConnection() {
    for (String cause : List.of("LIBRARY_DELETED", "PROFILE_CHANGED", "SELF", "SECRET_EXPIRED")) {
      assertThatCode(
              () ->
                  execute(
                      insertEnd(
                          "'DELETED'",
                          "'" + cause + "'",
                          "'LIBRARY'",
                          "NULL",
                          "gen_random_uuid()")))
          .as(cause)
          .doesNotThrowAnyException();
    }
    assertRejected(
        insertEnd("'DELETED'", "'BORED'", "'LIBRARY'", "NULL", "gen_random_uuid()"), CAUSE_CHECK);
    assertRejected(
        insertEnd("'DELETED'", "NULL", "'LIBRARY'", "NULL", "gen_random_uuid()"),
        "\"chk_connection_log_cause_of_end\"");
  }

  @Test
  void theOwnerKindHasNoSecondCheckOfItsOwn() throws Exception {
    assertThat(
            longOf(
                "SELECT count(*) FROM pg_constraint WHERE conname ="
                    + " 'chk_connection_log_owner_kind'"))
        .isZero();
  }

  @Test
  void rejectsAnUnknownOwnerKind() {
    assertRejected(insert("'GROUP'", "NULL", "NULL", "NULL"), CHECK);
  }

  /** The changeset takes the owner role with SET only for its statements and gives it back. */
  @Test
  void theMigrationAccountGivesTheSetMembershipBack() throws Exception {
    assertThat(
            longOf(
                "SELECT count(*) FROM pg_auth_members WHERE roleid = 'opaa_audit_owner'::regrole"
                    + " AND member = current_user::regrole AND set_option"))
        .isZero();
  }

  private static String insertEnd(
      String eventType, String cause, String ownerKind, String personRef, String libraryId) {
    return "INSERT INTO connection_log (event_id, organization_id, recorded_at, event_type,"
        + " actor_ref, owner_kind, person_ref, library_id, profile_id, profile_name, cause)"
        + " VALUES (gen_random_uuid(), '"
        + SEEDED_ORGANIZATION_ID
        + "', now(), "
        + eventType
        + ", 'actor', "
        + ownerKind
        + ", "
        + personRef
        + ", "
        + libraryId
        + ", gen_random_uuid(), 'Nextcloud', "
        + cause
        + ")";
  }

  private static String insert(
      String ownerKind, String personRef, String libraryId, String accountLabel) {
    return "INSERT INTO connection_log (event_id, organization_id, recorded_at, event_type,"
        + " actor_ref, owner_kind, person_ref, library_id, account_label, profile_id,"
        + " profile_name) VALUES (gen_random_uuid(), '"
        + SEEDED_ORGANIZATION_ID
        + "', now(), 'CONNECTED', 'actor', "
        + ownerKind
        + ", "
        + personRef
        + ", "
        + libraryId
        + ", "
        + accountLabel
        + ", gen_random_uuid(), 'Nextcloud')";
  }
}
