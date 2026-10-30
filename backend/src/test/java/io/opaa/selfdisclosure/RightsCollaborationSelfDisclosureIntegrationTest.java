package io.opaa.selfdisclosure;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.common.SelfDisclosureSectionReader;
import io.opaa.compliance.SelfDisclosureCategory;
import io.opaa.compliance.SelfDisclosureExporter;
import io.opaa.compliance.SelfDisclosureSubject;
import io.opaa.security.CredentialsEncryptor;
import io.opaa.security.EncryptedColumn;
import io.opaa.test.OpaaIntegrationTest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The exporters of the modules rights, workspace, connections and external through the real archive
 * and its positive-list sink, against Postgres: a person and a second account in a throwaway
 * organization, each with data in every category and in shared groups, spaces and libraries, and
 * every secret column filled with a marker. Each module's categories appear with German titles and
 * the person's own data; nothing of the second account, no id, pseudonym or secret.
 */
@OpaaIntegrationTest
class RightsCollaborationSelfDisclosureIntegrationTest {

  private static final Map<String, String> TITLES = new LinkedHashMap<>();

  static {
    TITLES.put("rights", "Rechte und eigene Assets");
    TITLES.put("permission-history", "Rechtehistorie");
    TITLES.put("groups", "Gruppen");
    TITLES.put("permission-transfers", "Rechteübertragungen");
    TITLES.put("succession", "Nachfolgefälle");
    TITLES.put("favorites", "Favoriten");
    TITLES.put("spaces", "Spaces");
    TITLES.put("space-membership-history", "Historie der Space-Mitgliedschaften");
    TITLES.put("connected-accounts", "Verbundene Konten");
    TITLES.put("connection-person-states", "Ruhezustand Ihrer Verbindungen");
    TITLES.put("connection-log", "Verbindungsprotokoll");
    TITLES.put("external-access-tokens", "Zugangstokens für externe KI-Werkzeuge");
  }

  /** The values of the secret columns; none may appear in the archive. */
  private static final List<String> SECRET_MARKERS =
      List.of(
          "SECRETPREFIX2991",
          "SECRETLOOKUPHASH2991",
          "SECRET-TOKEN-2991",
          "SECRET-ACCESS-2991",
          "SECRET-VERIFIER-2991",
          "SECRETSTATEHASH2991",
          "SECRET-REVOCATION-2991",
          "SECRET-CLIENT-2991",
          "SECRET-LIBCRED-2991",
          "SECRET-PENDING-2991");

  /** Names of the second account's own objects; none may appear in the archive. */
  private static final List<String> FOREIGN_OBJECTS =
      List.of(
          "sd2991 Fremdes Asset",
          "sd2991 Gruppe Fremd",
          "sd2991 Nur fremder Space",
          "sd2991 Label Fremdgruppe",
          "sd2991 Fremdes Token",
          "fremd-konto@anbieter.example",
          "fremd-bib-konto@anbieter.example",
          "sd2991 Fremde Anweisung");

  @Autowired private JdbcTemplate jdbc;
  @Autowired private List<SelfDisclosureExporter> exporters;
  @Autowired private CredentialsEncryptor encryptor;

  private UUID organization;
  private UUID person;
  private UUID other;
  private UUID third;
  private UUID personPseudonym;
  private UUID otherPseudonym;
  private UUID profile;
  private String personLabelCiphertext;
  private String otherLabelCiphertext;

  @BeforeEach
  void setUp() {
    organization = UUID.randomUUID();
    person = UUID.randomUUID();
    other = UUID.randomUUID();
    third = UUID.randomUUID();
    personPseudonym = UUID.randomUUID();
    otherPseudonym = UUID.randomUUID();
    profile = UUID.randomUUID();
    seed();
  }

  @AfterEach
  void cleanUp() {
    if (organization == null) {
      return;
    }
    jdbc.update("DELETE FROM connection_log WHERE organization_id = ?", organization);
    jdbc.update("DELETE FROM connection_grant_revocations WHERE profile_id = ?", profile);
    jdbc.update("DELETE FROM connection_authorizations WHERE profile_id = ?", profile);
    jdbc.update("DELETE FROM connection_tokens WHERE profile_id = ?", profile);
    jdbc.update(
        "DELETE FROM library_connections WHERE library_id IN"
            + " (SELECT id FROM assets WHERE organization_id = ?)",
        organization);
    jdbc.update("DELETE FROM connected_accounts WHERE organization_id = ?", organization);
    jdbc.update("DELETE FROM connection_profiles WHERE id = ?", profile);
    jdbc.update("DELETE FROM connection_person_states WHERE user_id IN (?, ?)", person, other);
    jdbc.update("DELETE FROM external_access_tokens WHERE user_id IN (?, ?)", person, other);
    for (String table :
        List.of(
            "succession_cases",
            "permission_transfers",
            "asset_grant_history",
            "asset_ownership_history",
            "asset_visibility_history",
            "capability_grant_history",
            "group_membership_history",
            "space_membership_history",
            "capability_grants",
            "asset_favorites",
            "asset_grants",
            "space_asset_associations",
            "space_memberships",
            "spaces",
            "knowledge_libraries",
            "assets",
            "group_stewards",
            "group_memberships",
            "groups",
            "audit_actor_pseudonyms",
            "account_state_history",
            "audit_log",
            "users")) {
      jdbc.update("DELETE FROM " + table + " WHERE organization_id = ?", organization);
    }
    jdbc.update("DELETE FROM organizations WHERE id = ?", organization);
    organization = null;
  }

  @Test
  void everyCategoryOfTheFourModulesAppearsWithItsGermanTitleAndFieldLabels() throws IOException {
    Map<String, String> entries = export(person);

    for (Map.Entry<String, String> category : TITLES.entrySet()) {
      String json = entries.get(category.getKey() + ".json");
      assertThat(json).as(category.getKey()).isNotNull();
      assertThat(json)
          .contains("\"category\":\"" + category.getKey() + "\"")
          .contains("\"title\":\"" + category.getValue() + "\"");
      SelfDisclosureCategory definition = exporter(category.getKey()).category();
      definition.sections().stream()
          .filter(section -> json.contains("\"key\":\"" + section.key() + "\""))
          .flatMap(section -> section.fields().stream())
          .forEach(field -> assertThat(json).contains("\"label\":\"" + field.label() + "\""));
    }
  }

  @Test
  void theRightsModuleExportsOwnAssetsGrantsHistoryGroupsTransfersSuccessionAndFavorites()
      throws IOException {
    Map<String, String> entries = export(person);

    assertThat(entries.get("rights.json"))
        .contains("\"name\":\"sd2991 Meine Prompts\"", "PROMPT_LIBRARY")
        .contains("\"asset\":\"sd2991 Geteiltes Asset\"", "\"grantedBy\":\"andere Person\"")
        .contains("\"recipient\":\"andere Person\"", "\"recipientGroup\":\"sd2991 Gruppe Mein\"")
        .contains("\"capability\":\"CREATE_SPACE\"")
        .contains("\"name\":\"sd2991 Angelegt für andere\"", "\"createdBy\":\"Sie\"")
        .doesNotContain("Prompttext");
    // a grant another person gave a third one on the person's asset is not hers
    assertThat(records(entries.get("rights.json"), "updatedAt")).isEqualTo(3);
    assertThat(entries.get("permission-history.json"))
        .contains("\"actor\":\"andere Person\"", "\"actor\":\"Sie\"", "\"actor\":\"System\"")
        .contains("\"group\":\"sd2991 Gruppe Mein\"", "\"cause\":\"EXTERNAL_ACCESS_CHANGED\"");
    assertThat(entries.get("groups.json"))
        .contains("\"group\":\"sd2991 Gruppe Mein\"", "\"appointedBy\":\"andere Person\"");
    assertThat(entries.get("permission-transfers.json"))
        .contains("\"target\":\"Sie\"", "\"source\":\"andere Person\"", "\"sourceType\":\"GROUP\"");
    assertThat(records(entries.get("permission-transfers.json"), "performedAt")).isEqualTo(2);
    assertThat(entries.get("succession.json"))
        .contains("\"object\":\"sd2991 Meine Prompts\"")
        .contains("\"object\":\"sd2991 Space Abgeschlossen\"", "\"closedBy\":\"Sie\"");
    assertThat(records(entries.get("succession.json"), "firstSeenAt")).isEqualTo(2);
    assertThat(entries.get("favorites.json")).contains("\"asset\":\"sd2991 Geteiltes Asset\"");
    assertThat(records(entries.get("favorites.json"), "asset")).isEqualTo(1);
  }

  @Test
  void aSuccessionCaseGoesToWhoOwnedTheAssetWhenItWasFirstSeenNotToTheSuccessor()
      throws IOException {
    UUID takenOver = asset("PROMPT_LIBRARY", "sd2991 Übernommenes Asset", person);
    UUID handedOn = asset("PROMPT_LIBRARY", "sd2991 Abgegebenes Asset", other);
    transferredCase(takenOver, other, person);
    transferredCase(handedOn, person, other);

    String mine = export(person).get("succession.json");
    String theirs = export(other).get("succession.json");

    assertThat(mine)
        .contains("\"object\":\"sd2991 Abgegebenes Asset\"")
        .doesNotContain("sd2991 Übernommenes Asset");
    assertThat(theirs)
        .contains("\"object\":\"sd2991 Übernommenes Asset\"")
        .doesNotContain("sd2991 Abgegebenes Asset");
  }

  @Test
  void aSectionLongerThanOnePageArrivesCompleteAlsoWithOneSharedTime() throws IOException {
    UUID group =
        jdbc.queryForObject(
            "SELECT id FROM groups WHERE name = ? AND organization_id = ?",
            UUID.class,
            "sd2991 Gruppe Mein",
            organization);
    jdbc.update(
        "INSERT INTO group_membership_history (id, group_id, organization_id, user_id, cause,"
            + " actor_user_id, valid_from, valid_to) SELECT gen_random_uuid(), ?, ?, ?, 'ADDED', NULL,"
            + " now(), now() FROM generate_series(1, ?)",
        group,
        organization,
        person,
        2 * SelfDisclosureSectionReader.PAGE_SIZE + 1);

    String history = export(person).get("permission-history.json");

    assertThat(records(history, "member")).isEqualTo(2 * SelfDisclosureSectionReader.PAGE_SIZE + 2);
  }

  @Test
  void theWorkspaceModuleExportsOwnSpacesMembershipsAssignmentsAndHistory() throws IOException {
    Map<String, String> entries = export(person);

    assertThat(entries.get("spaces.json"))
        .contains("\"name\":\"sd2991 Mein Space\"", "\"modelInstruction\":\"sd2991 Anweisung\"")
        .contains("\"space\":\"sd2991 Gemeinsamer Space\"")
        .contains("\"asset\":\"sd2991 Meine Prompts\"");
    assertThat(entries.get("space-membership-history.json"))
        .contains("\"member\":\"Sie\"", "\"member\":\"andere Person\"", "\"actor\":\"Sie\"");
    assertThat(records(entries.get("space-membership-history.json"), "validFrom")).isEqualTo(2);
  }

  @Test
  void theConnectionsModuleExportsAccountsLibrariesStateAndLogWithoutForeignAccountNames()
      throws IOException {
    Map<String, String> entries = export(person);

    assertThat(entries.get("connected-accounts.json"))
        .contains("\"accountLabel\":\"ich-konto@anbieter.example\"")
        .contains("\"accountLabel\":\"ich-bib-konto@anbieter.example\"")
        .contains("\"library\":\"sd2991 Fremd verbundene Bibliothek\"")
        .contains("\"connectedBy\":\"andere Person\"", "\"responsible\":\"Sie\"");
    assertThat(entries.get("connection-person-states.json")).contains("\"dormantSince\":\"");
    String log = entries.get("connection-log.json");
    assertThat(log)
        .contains("\"actor\":\"Sie\"", "\"actor\":\"System\"", "\"actor\":\"andere Person\"")
        .contains("\"person\":\"Sie\"", "\"profile\":\"sd2991 Zugang\"");
    assertThat(records(log, "recordedAt")).isEqualTo(3);
  }

  @Test
  void theExternalModuleExportsOwnTokensWithRevocation() throws IOException {
    Map<String, String> entries = export(person);

    assertThat(entries.get("external-access-tokens.json"))
        .contains("\"name\":\"sd2991 Mein Token\"", "\"revocationReason\":\"OWNER\"")
        .contains("\"lastUsedOn\":\"");
    assertThat(records(entries.get("external-access-tokens.json"), "name")).isEqualTo(1);
  }

  @Test
  void noIdNamePseudonymOrObjectOfAnotherAccountAndNoOwnIdOrPseudonymAppears() throws IOException {
    String all = String.join("\n", export(person).values());

    assertThat(all)
        .doesNotContain(other.toString(), otherPseudonym.toString())
        .doesNotContain("sd2991-fremd", "fremd.person@sd2991.example", "Frieda Fremdperson")
        .doesNotContain(person.toString(), personPseudonym.toString())
        .doesNotContain(third.toString(), "Theo Dritter", "sd2991-dritt")
        .doesNotContain("ida.ich@sd2991.example", "Ida Ichperson");
    for (String foreign : FOREIGN_OBJECTS) {
      assertThat(all).as(foreign).doesNotContain(foreign);
    }
  }

  @Test
  void noSecretColumnValueAppears() throws IOException {
    String all = String.join("\n", export(person).values());

    for (String secret : SECRET_MARKERS) {
      assertThat(all).as(secret).doesNotContain(secret);
    }
    assertThat(all).doesNotContain(personLabelCiphertext, otherLabelCiphertext);
  }

  private Map<String, String> export(UUID account) throws IOException {
    SelfDisclosureSubject subject = new SelfDisclosureSubject(account, organization);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    SelfDisclosureArchive archive = new SelfDisclosureArchive(out, ZoneOffset.UTC, () -> {});
    for (String id : TITLES.keySet()) {
      SelfDisclosureExporter exporter = exporter(id);
      archive.category(exporter.category(), sink -> exporter.export(subject, sink));
    }
    archive.finish(new byte[0], new byte[0]);
    Map<String, String> entries = new LinkedHashMap<>();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
      for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
        entries.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
    return entries;
  }

  private SelfDisclosureExporter exporter(String id) {
    return exporters.stream()
        .filter(e -> e.category().id().equals(id))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no exporter of " + id));
  }

  /** Records of a category JSON, counted by a field key every record of it carries. */
  private static int records(String json, String key) {
    return json.split("\"" + key + "\":", -1).length - 1;
  }

  private void seed() {
    jdbc.update("INSERT INTO organizations (id, name) VALUES (?, ?)", organization, "sd2991");
    user(person, "sd2991-ich-" + person, "ida.ich@sd2991.example", "Ida Ichperson");
    user(other, "sd2991-fremd-" + other, "fremd.person@sd2991.example", "Frieda Fremdperson");
    user(third, "sd2991-dritt-" + third, "dritte.person@sd2991.example", "Theo Dritter");
    jdbc.update(
        "INSERT INTO audit_actor_pseudonyms (pseudonym_id, user_id, organization_id)"
            + " VALUES (?, ?, ?), (?, ?, ?)",
        personPseudonym,
        person,
        organization,
        otherPseudonym,
        other,
        organization);

    UUID ownGroup = group("sd2991 Gruppe Mein");
    UUID foreignGroup = group("sd2991 Gruppe Fremd");
    membership(ownGroup, person);
    membership(ownGroup, other);
    membership(foreignGroup, other);
    jdbc.update(
        "INSERT INTO group_stewards (id, group_id, user_id, organization_id, appointed_by_user_id)"
            + " VALUES (?, ?, ?, ?, ?), (?, ?, ?, ?, NULL)",
        UUID.randomUUID(),
        ownGroup,
        person,
        organization,
        other,
        UUID.randomUUID(),
        foreignGroup,
        other,
        organization);

    UUID prompts = asset("PROMPT_LIBRARY", "sd2991 Meine Prompts", person);
    UUID foreign = asset("PROMPT_LIBRARY", "sd2991 Fremdes Asset", other);
    UUID shared = asset("PROMPT_LIBRARY", "sd2991 Geteiltes Asset", other);
    UUID library = library("sd2991 Meine Bibliothek", person);
    UUID foreignConnected = library("sd2991 Fremd verbundene Bibliothek", other);
    grant(shared, "USER", person, null, other);
    grant(prompts, "USER", other, null, person);
    grant(prompts, "GROUP", null, ownGroup, person);
    grant(foreign, "GROUP", null, foreignGroup, other);
    grant(prompts, "USER", third, null, other);
    asset("PROMPT_LIBRARY", "sd2991 Angelegt für andere", other, person);
    jdbc.update(
        "INSERT INTO capability_grants (id, organization_id, capability, subject_type,"
            + " subject_user_id, granted_by_user_id) VALUES (?, ?, 'CREATE_SPACE', 'USER', ?, ?),"
            + " (?, ?, 'CREATE_LIBRARY', 'USER', ?, ?)",
        UUID.randomUUID(),
        organization,
        person,
        other,
        UUID.randomUUID(),
        organization,
        other,
        other);
    jdbc.update(
        "INSERT INTO asset_favorites (asset_id, user_id, organization_id) VALUES (?, ?, ?),"
            + " (?, ?, ?)",
        shared,
        person,
        organization,
        foreign,
        other,
        organization);

    seedHistory(prompts, foreign, shared, ownGroup, foreignGroup);
    seedTransfersAndSuccession(prompts, foreign, foreignGroup);
    seedSpaces(prompts, foreign);
    seedConnections(library, foreignConnected);
    seedTokens();
  }

  private void seedHistory(
      UUID prompts, UUID foreign, UUID shared, UUID ownGroup, UUID foreignGroup) {
    String grantHistory =
        "INSERT INTO asset_grant_history (id, asset_id, organization_id, subject_type,"
            + " subject_user_id, role, cause, actor_user_id, valid_from, asset_type)"
            + " VALUES (?, ?, ?, 'USER', ?, 'VIEWER', 'GRANTED', ?, now(), 'PROMPT_LIBRARY')";
    jdbc.update(grantHistory, UUID.randomUUID(), shared, organization, person, other);
    jdbc.update(grantHistory, UUID.randomUUID(), foreign, organization, other, other);
    String ownership =
        "INSERT INTO asset_ownership_history (id, asset_type, asset_id, organization_id,"
            + " owner_type, owner_user_id, cause, actor_user_id, valid_from)"
            + " VALUES (?, 'PROMPT_LIBRARY', ?, ?, 'USER', ?, 'CREATED', ?, now())";
    jdbc.update(ownership, UUID.randomUUID(), prompts, organization, person, person);
    jdbc.update(ownership, UUID.randomUUID(), foreign, organization, other, other);
    String visibility =
        "INSERT INTO asset_visibility_history (id, asset_id, organization_id, cause,"
            + " actor_user_id, valid_from, asset_type)"
            + " VALUES (?, ?, ?, 'EXTERNAL_ACCESS_CHANGED', ?, now(), 'PROMPT_LIBRARY')";
    jdbc.update(visibility, UUID.randomUUID(), prompts, organization, person);
    jdbc.update(visibility, UUID.randomUUID(), foreign, organization, other);
    jdbc.update(
        "INSERT INTO capability_grant_history (id, organization_id, capability, subject_type,"
            + " subject_user_id, cause, actor_user_id, valid_from)"
            + " VALUES (?, ?, 'CREATE_SPACE', 'USER', ?, 'GRANTED', ?, now())",
        UUID.randomUUID(),
        organization,
        person,
        other);
    String membershipHistory =
        "INSERT INTO group_membership_history (id, group_id, organization_id, user_id, cause,"
            + " actor_user_id, valid_from) VALUES (?, ?, ?, ?, 'ADDED', ?, now())";
    jdbc.update(membershipHistory, UUID.randomUUID(), ownGroup, organization, person, null);
    jdbc.update(membershipHistory, UUID.randomUUID(), foreignGroup, organization, other, other);
  }

  private void seedTransfersAndSuccession(UUID prompts, UUID foreign, UUID foreignGroup) {
    jdbc.update(
        "INSERT INTO permission_transfers (id, organization_id, source_type, source_user_id,"
            + " target_type, target_user_id, scope, grant_count, actor_user_id, performed_at)"
            + " VALUES (?, ?, 'USER', ?, 'USER', ?, 'GRANTS', 3, ?, now())",
        UUID.randomUUID(),
        organization,
        other,
        person,
        other);
    jdbc.update(
        "INSERT INTO permission_transfers (id, organization_id, source_type, source_group_id,"
            + " source_label, target_type, target_user_id, scope, actor_user_id, performed_at)"
            + " VALUES (?, ?, 'GROUP', ?, 'sd2991 Label Fremdgruppe', 'USER', ?, 'GRANTS', NULL,"
            + " now())",
        UUID.randomUUID(),
        organization,
        foreignGroup,
        person);
    jdbc.update(
        "INSERT INTO permission_transfers (id, organization_id, source_type, source_group_id,"
            + " source_label, target_type, target_user_id, scope, actor_user_id, performed_at)"
            + " VALUES (?, ?, 'GROUP', ?, 'sd2991 Label Fremdgruppe', 'USER', ?, 'GRANTS', ?,"
            + " now())",
        UUID.randomUUID(),
        organization,
        foreignGroup,
        other,
        other);
    UUID closedSpace = space("sd2991 Space Abgeschlossen", other, null);
    String succession =
        "INSERT INTO succession_cases (id, organization_id, kind, object_type, object_id,"
            + " asset_type, first_seen_at, last_seen_at, closed_at, closed_by_user_id)"
            + " VALUES (?, ?, 'OPEN_SUCCESSION', ?, ?, ?, now(), now(), ?, ?)";
    jdbc.update(
        succession,
        UUID.randomUUID(),
        organization,
        "ASSET",
        prompts,
        "PROMPT_LIBRARY",
        null,
        null);
    jdbc.update(
        succession,
        UUID.randomUUID(),
        organization,
        "ASSET",
        foreign,
        "PROMPT_LIBRARY",
        null,
        null);
    jdbc.update(
        succession,
        UUID.randomUUID(),
        organization,
        "SPACE",
        closedSpace,
        null,
        java.sql.Timestamp.from(java.time.Instant.now()),
        person);
  }

  private void seedSpaces(UUID prompts, UUID foreign) {
    UUID own = space("sd2991 Mein Space", person, "sd2991 Anweisung");
    UUID shared = space("sd2991 Gemeinsamer Space", other, "sd2991 Fremde Anweisung");
    UUID foreignOnly = space("sd2991 Nur fremder Space", other, null);
    spaceMember(own, person, "ADMIN");
    spaceMember(shared, person, "MEMBER");
    spaceMember(shared, other, "ADMIN");
    spaceMember(foreignOnly, other, "ADMIN");
    String association =
        "INSERT INTO space_asset_associations (id, space_id, asset_id, organization_id,"
            + " created_by_user_id) VALUES (?, ?, ?, ?, ?)";
    jdbc.update(association, UUID.randomUUID(), own, prompts, organization, person);
    jdbc.update(association, UUID.randomUUID(), foreignOnly, foreign, organization, other);
    String history =
        "INSERT INTO space_membership_history (id, space_id, organization_id, subject_type,"
            + " subject_user_id, role, cause, actor_user_id, valid_from)"
            + " VALUES (?, ?, ?, 'USER', ?, 'ADMIN', 'ADDED', ?, now())";
    jdbc.update(history, UUID.randomUUID(), own, organization, person, person);
    jdbc.update(history, UUID.randomUUID(), shared, organization, other, person);
    jdbc.update(history, UUID.randomUUID(), foreignOnly, organization, other, other);
  }

  private void seedConnections(UUID library, UUID foreignConnected) {
    jdbc.update(
        "INSERT INTO connection_profiles (id, name, source_type, server_url, auth_method,"
            + " ownership, client_secret_ciphertext, created_at, updated_at, version)"
            + " VALUES (?, 'sd2991 Zugang', 'PERSON_PROBE', 'https://anbieter.example',"
            + " 'PERSONAL_SECRET', 'BOTH', 'SECRET-CLIENT-2991', now(), now(), 0)",
        profile);
    UUID personAccount = UUID.randomUUID();
    UUID otherAccount = UUID.randomUUID();
    EncryptedColumn label =
        EncryptedColumn.credentials("connected_accounts", "account_label_ciphertext");
    personLabelCiphertext =
        encryptor.encrypt("ich-konto@anbieter.example", label.row(personAccount));
    otherLabelCiphertext =
        encryptor.encrypt("fremd-konto@anbieter.example", label.row(otherAccount));
    String account =
        "INSERT INTO connected_accounts (id, organization_id, user_id, profile_id, state,"
            + " account_label_ciphertext, connected_at, version)"
            + " VALUES (?, ?, ?, ?, 'CONNECTED', ?, now(), 0)";
    jdbc.update(account, personAccount, organization, person, profile, personLabelCiphertext);
    jdbc.update(account, otherAccount, organization, other, profile, otherLabelCiphertext);
    jdbc.update(
        "INSERT INTO connection_tokens (id, profile_id, connected_account_id, kind,"
            + " secret_ciphertext, access_token_ciphertext, issued_for, pending_account_label,"
            + " created_at, updated_at, version) VALUES (?, ?, ?, 'OAUTH', 'SECRET-TOKEN-2991',"
            + " 'SECRET-ACCESS-2991', 'https://anbieter.example', 'SECRET-PENDING-2991', now(),"
            + " now(), 0)",
        UUID.randomUUID(),
        profile,
        personAccount);
    jdbc.update(
        "INSERT INTO connection_authorizations (id, state_hash, user_id, profile_id,"
            + " profile_version, purpose, code_verifier_ciphertext, redirect_uri, created_at,"
            + " expires_at) VALUES (?, rpad('SECRETSTATEHASH2991', 64, 'x'), ?, ?, 0, 'ACCOUNT',"
            + " 'SECRET-VERIFIER-2991', 'https://opaa.example/callback', now(),"
            + " now() + interval '5 minutes')",
        UUID.randomUUID(),
        person,
        profile);
    jdbc.update(
        "INSERT INTO connection_grant_revocations (id, profile_id, order_ciphertext)"
            + " VALUES (?, ?, 'SECRET-REVOCATION-2991')",
        UUID.randomUUID(),
        profile);
    String libraryConnection =
        "INSERT INTO library_connections (library_id, profile_id, created_at, updated_at,"
            + " version, account_label, connected_by, connected_at, responsible_type,"
            + " responsible_id) VALUES (?, ?, now(), now(), 0, ?, ?, now(), 'USER', ?)";
    jdbc.update(
        libraryConnection, library, profile, "ich-bib-konto@anbieter.example", person, person);
    jdbc.update(
        libraryConnection,
        foreignConnected,
        profile,
        "fremd-bib-konto@anbieter.example",
        other,
        person);
    jdbc.update(
        "INSERT INTO connection_person_states (user_id, dormant_since, checked_at)"
            + " VALUES (?, now(), now()), (?, NULL, now())",
        person,
        other);
    String log =
        "INSERT INTO connection_log (event_id, organization_id, recorded_at, event_type,"
            + " actor_ref, owner_kind, person_ref, library_id, account_label, profile_id,"
            + " profile_name, cause) VALUES (?, ?, now(), ?, ?, ?, ?, ?, ?, ?, 'sd2991 Zugang', ?)";
    jdbc.update(
        log,
        UUID.randomUUID(),
        organization,
        "CONNECTED",
        personPseudonym.toString(),
        "PERSON",
        personPseudonym.toString(),
        null,
        null,
        profile,
        null);
    jdbc.update(
        log,
        UUID.randomUUID(),
        organization,
        "EXPIRED",
        "SYSTEM",
        "PERSON",
        personPseudonym.toString(),
        null,
        null,
        profile,
        "SECRET_EXPIRED");
    jdbc.update(
        log,
        UUID.randomUUID(),
        organization,
        "EMERGENCY_DISCONNECTED",
        otherPseudonym.toString(),
        "PERSON",
        personPseudonym.toString(),
        null,
        null,
        profile,
        "EMERGENCY");
    jdbc.update(
        log,
        UUID.randomUUID(),
        organization,
        "CONNECTED",
        otherPseudonym.toString(),
        "LIBRARY",
        null,
        foreignConnected,
        "fremd-bib-konto@anbieter.example",
        profile,
        null);
  }

  private void seedTokens() {
    jdbc.update(
        "INSERT INTO external_access_tokens (id, user_id, name, token_prefix, token_lookup_hash,"
            + " expires_at, last_used_on, revoked_at, revocation_reason) VALUES (?, ?,"
            + " 'sd2991 Mein Token', 'SECRETPREFIX2991', 'SECRETLOOKUPHASH2991',"
            + " now() + interval '30 days', current_date, now(), 'OWNER')",
        UUID.randomUUID(),
        person);
    jdbc.update(
        "INSERT INTO external_access_tokens (id, user_id, name, token_prefix, token_lookup_hash,"
            + " expires_at) VALUES (?, ?, 'sd2991 Fremdes Token', 'OTHERPREFIX2991',"
            + " 'OTHERLOOKUPHASH2991', now() + interval '30 days')",
        UUID.randomUUID(),
        other);
  }

  /**
   * {@code asset} belonged to {@code before} two days ago and to {@code after} since yesterday; a
   * succession case opened in between and closed with the transfer, by nobody of the two.
   */
  private void transferredCase(UUID asset, UUID before, UUID after) {
    String ownership =
        "INSERT INTO asset_ownership_history (id, asset_type, asset_id, organization_id,"
            + " owner_type, owner_user_id, cause, actor_user_id, valid_from, valid_to)"
            + " VALUES (?, 'PROMPT_LIBRARY', ?, ?, 'USER', ?, ?, NULL, now() - ?::interval,"
            + " now() - ?::interval)";
    jdbc.update(
        ownership, UUID.randomUUID(), asset, organization, before, "CREATED", "2 days", "1 day");
    jdbc.update(
        ownership.replace(", now() - ?::interval)", ", NULL)"),
        UUID.randomUUID(),
        asset,
        organization,
        after,
        "TRANSFERRED",
        "1 day");
    jdbc.update(
        "INSERT INTO succession_cases (id, organization_id, kind, object_type, object_id,"
            + " asset_type, first_seen_at, last_seen_at, closed_at, closed_by_user_id)"
            + " VALUES (?, ?, 'OPEN_SUCCESSION', 'ASSET', ?, 'PROMPT_LIBRARY',"
            + " now() - interval '36 hours', now() - interval '1 day', now() - interval '1 day',"
            + " NULL)",
        UUID.randomUUID(),
        organization,
        asset);
  }

  private void user(UUID id, String subject, String email, String name) {
    jdbc.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, organization_id)"
            + " VALUES (?, ?, 'sd2991', ?, ?, ?)",
        id,
        subject,
        email,
        name,
        organization);
  }

  private UUID group(String name) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO groups (id, organization_id, kind, name) VALUES (?, ?, 'AD_HOC', ?)",
        id,
        organization,
        name);
    return id;
  }

  private void membership(UUID group, UUID user) {
    jdbc.update(
        "INSERT INTO group_memberships (id, user_id, group_id, organization_id)"
            + " VALUES (?, ?, ?, ?)",
        UUID.randomUUID(),
        user,
        group,
        organization);
  }

  private UUID asset(String type, String name, UUID owner) {
    return asset(type, name, owner, owner);
  }

  private UUID asset(String type, String name, UUID owner, UUID creator) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO assets (id, asset_type, organization_id, name, description, owner_type,"
            + " owner_user_id, created_by_user_id) VALUES (?, ?, ?, ?, 'Prompttext', 'USER', ?, ?)",
        id,
        type,
        organization,
        name,
        owner,
        creator);
    return id;
  }

  private UUID library(String name, UUID owner) {
    UUID id = asset("KNOWLEDGE_LIBRARY", name, owner);
    jdbc.update(
        "INSERT INTO knowledge_libraries (id, organization_id, source_type, source_url, source_credentials)"
            + " VALUES (?, ?, 'PERSON_PROBE', 'https://anbieter.example/bib',"
            + " 'SECRET-LIBCRED-2991')",
        id,
        organization);
    return id;
  }

  private void grant(UUID asset, String type, UUID user, UUID group, UUID grantedBy) {
    jdbc.update(
        "INSERT INTO asset_grants (id, asset_id, organization_id, subject_type, subject_user_id,"
            + " subject_group_id, role, granted_by_user_id, asset_type)"
            + " VALUES (?, ?, ?, ?, ?, ?, 'VIEWER', ?,"
            + " (SELECT asset_type FROM assets WHERE id = ?))",
        UUID.randomUUID(),
        asset,
        organization,
        type,
        user,
        group,
        grantedBy,
        asset);
  }

  private UUID space(String name, UUID owner, String instruction) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO spaces (id, name, owner_id, organization_id, model_instruction)"
            + " VALUES (?, ?, ?, ?, ?)",
        id,
        name,
        owner,
        organization,
        instruction);
    return id;
  }

  private void spaceMember(UUID space, UUID user, String role) {
    jdbc.update(
        "INSERT INTO space_memberships (id, user_id, space_id, role, organization_id,"
            + " subject_type) VALUES (?, ?, ?, ?, ?, 'USER')",
        UUID.randomUUID(),
        user,
        space,
        role,
        organization);
  }
}
