package io.opaa.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.api.types.LockReason;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.DevAuthFilter;
import io.opaa.auth.ErasedAccount;
import io.opaa.auth.LocalCredentials;
import io.opaa.auth.LocalCredentialsRepository;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.organization.Organization;
import io.opaa.space.SpaceService;
import io.opaa.test.OpaaIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The erasure of an account through the real endpoint: a deactivated account loses name, address
 * and its identity at the provider, and with them its chats, personal space, credentials and the
 * other data of the person, while the row stays; a usable account, one with a system role and one
 * whose chat deletion is held are refused and keep everything. Every row is this class's own.
 */
@OpaaIntegrationTest
class AccountErasureIntegrationTest {

  private static final String ERASURE = "/api/v1/admin/accounts/%s/erasure";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private LocalCredentialsRepository credentials;
  @Autowired private SpaceService spaces;
  @Autowired private JdbcTemplate jdbc;

  private final String marker = "erase-" + UUID.randomUUID().toString().substring(0, 8);
  private final List<UUID> createdUsers = new ArrayList<>();
  private final List<UUID> createdLibraries = new ArrayList<>();

  @BeforeEach
  void provisionAdmin() throws Exception {
    // provisions dev-admin as SYSTEM_ADMIN in the default organization
    mockMvc
        .perform(get("/api/v1/admin/accounts").with(dev("dev-admin")))
        .andExpect(status().isOk());
  }

  @AfterEach
  void cleanUp() {
    for (UUID library : createdLibraries) {
      jdbc.update("DELETE FROM assets WHERE id = ?", library);
    }
    for (UUID user : createdUsers) {
      jdbc.update("DELETE FROM chats WHERE author_id = ?", user);
      jdbc.update("DELETE FROM chat_cleanup_holds WHERE user_id = ?", user);
      jdbc.update("DELETE FROM chat_cleanup_hold_requests WHERE user_id = ?", user);
      jdbc.update("DELETE FROM notifications WHERE object_id = ?", user);
      jdbc.update("DELETE FROM spaces WHERE owner_id = ?", user);
      jdbc.update("DELETE FROM space_membership_history WHERE subject_user_id = ?", user);
      jdbc.update("DELETE FROM asset_ownership_history WHERE owner_user_id = ?", user);
      jdbc.update("DELETE FROM users WHERE id = ?", user);
    }
  }

  @Test
  void removesThePersonalReferenceOfAnAccountWhoseProviderIsGone() throws Exception {
    UUID person = providerAccount("gone-" + marker, SystemRole.USER);
    spaces.ensureDefaultSpace(person, Organization.DEFAULT_ID);
    UUID personalSpace =
        jdbc.queryForObject(
            "SELECT id FROM spaces WHERE owner_id = ? AND is_default", UUID.class, person);
    jdbc.update(
        "INSERT INTO chats (id, space_id, author_id, organization_id, use_knowledge, status,"
            + " created_at, updated_at) VALUES (?, ?, ?, ?, true, 'PRIVATE', now(), now())",
        UUID.randomUUID(),
        personalSpace,
        person,
        Organization.DEFAULT_ID);
    jdbc.update(
        "INSERT INTO transcription_vocabularies (user_id, organization_id, vocabulary, updated_at)"
            + " VALUES (?, ?, 'Meldewesen', now())",
        person,
        Organization.DEFAULT_ID);
    jdbc.update(
        "INSERT INTO notifications (id, organization_id, recipient_user_id, type, title)"
            + " VALUES (?, ?, ?, 'TEST', 'Hinweis')",
        UUID.randomUUID(),
        Organization.DEFAULT_ID,
        person);
    UUID library = privateLibraryOf(person);

    mockMvc
        .perform(post(ERASURE.formatted(person)).with(dev("dev-admin")))
        .andExpect(status().isNoContent());

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT subject, issuer, email, display_name, erased_at FROM users WHERE id = ?",
            person);
    assertThat(row.get("display_name")).isEqualTo(ErasedAccount.DISPLAY_NAME);
    assertThat(row.get("email")).isNull();
    assertThat(row.get("issuer")).isEqualTo(ErasedAccount.ISSUER);
    assertThat(row.get("subject")).isEqualTo(person.toString());
    assertThat(row.get("erased_at")).isNotNull();
    assertThat(count("SELECT count(*) FROM chats WHERE author_id = ?", person)).isZero();
    assertThat(count("SELECT count(*) FROM spaces WHERE owner_id = ?", person)).isZero();
    assertThat(count("SELECT count(*) FROM transcription_vocabularies WHERE user_id = ?", person))
        .isZero();
    assertThat(count("SELECT count(*) FROM notifications WHERE recipient_user_id = ?", person))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT erasure_cause FROM knowledge_libraries WHERE id = ?",
                String.class,
                library))
        .isEqualTo("ACCOUNT_ERASED");
    assertThat(
            count(
                "SELECT count(*) FROM audit_log a JOIN audit_actor_pseudonyms p"
                    + " ON a.object_id = CAST(p.pseudonym_id AS text)"
                    + " WHERE a.event_type = 'ACCOUNT_ERASED' AND p.user_id = ?",
                person))
        .isEqualTo(1);

    // gone from every list, and a second erasure finds nothing
    mockMvc
        .perform(get("/api/v1/admin/accounts").with(dev("dev-admin")).param("query", "Gelöscht"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
    mockMvc
        .perform(post(ERASURE.formatted(person)).with(dev("dev-admin")))
        .andExpect(status().isNotFound());
    // no role can name the shell again
    mockMvc
        .perform(
            post("/api/v1/admin/users/%s/role".formatted(person))
                .with(dev("dev-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"SYSTEM_ADMIN\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void removesNotificationsToOthersThatNameTheAccount() throws Exception {
    UUID person = providerAccount("gone-" + marker, SystemRole.USER);
    UUID colleague = providerAccount("opaa-dev", SystemRole.USER);
    jdbc.update(
        "INSERT INTO notifications (id, organization_id, recipient_user_id, type, object_type,"
            + " object_id, title) VALUES (?, ?, ?, 'TEST', 'USER_ACCOUNT', ?, 'Antrag')",
        UUID.randomUUID(),
        Organization.DEFAULT_ID,
        colleague,
        person);

    mockMvc
        .perform(post(ERASURE.formatted(person)).with(dev("dev-admin")))
        .andExpect(status().isNoContent());

    assertThat(count("SELECT count(*) FROM notifications WHERE recipient_user_id = ?", colleague))
        .isZero();
  }

  @Test
  void refusesTheCallersOwnAccount() throws Exception {
    UUID admin =
        jdbc.queryForObject("SELECT id FROM users WHERE subject = 'dev-admin'", UUID.class);

    mockMvc
        .perform(post(ERASURE.formatted(admin)).with(dev("dev-admin")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SELF_ERASE"));
  }

  @Test
  void refusesWhileAHoldOfTheChatDeletionIsRequested() throws Exception {
    UUID person = providerAccount("gone-" + marker, SystemRole.USER);
    UUID admin =
        jdbc.queryForObject("SELECT id FROM users WHERE subject = 'dev-admin'", UUID.class);
    jdbc.update(
        "INSERT INTO chat_cleanup_hold_requests (user_id, organization_id, purpose, reason,"
            + " requested_by_user_id, requested_at) VALUES (?, ?, 'AUTHORITY_OR_COURT_ORDER',"
            + " 'Anordnung des Gerichts', ?, now())",
        person,
        Organization.DEFAULT_ID,
        admin);

    refused(person, "CHAT_CLEANUP_HELD");
  }

  @Test
  void removesTheCredentialsOfALockedLocalAccount() throws Exception {
    Instant now = Instant.now();
    User local = User.localAccount(marker + "-erika@stadt.example", marker + " Erika");
    local.setOrganizationId(Organization.DEFAULT_ID);
    UUID person = users.save(local).getId();
    createdUsers.add(person);
    LocalCredentials row = new LocalCredentials(person, "Vertretung Meldewesen", now);
    row.setPasswordHash("not-a-real-hash", now);
    row.lock(LockReason.ADMIN, now, null);
    credentials.save(row);

    mockMvc
        .perform(post(ERASURE.formatted(person)).with(dev("dev-admin")))
        .andExpect(status().isNoContent());

    assertThat(credentials.findById(person)).isEmpty();
    assertThat(users.findById(person).orElseThrow().isErased()).isTrue();
  }

  @Test
  void refusesAnAccountThatIsStillUsable() throws Exception {
    UUID person = providerAccount("opaa-dev", SystemRole.USER);

    refused(person, "ACCOUNT_ACTIVE");
  }

  @Test
  void refusesAnAccountWithASystemRole() throws Exception {
    UUID person = providerAccount("gone-" + marker, SystemRole.AUDITOR);

    refused(person, "SYSTEM_ROLE_HELD");
  }

  @Test
  void refusesWhileTheChatDeletionIsHeldAndKeepsTheChats() throws Exception {
    UUID person = providerAccount("gone-" + marker, SystemRole.USER);
    spaces.ensureDefaultSpace(person, Organization.DEFAULT_ID);
    UUID personalSpace =
        jdbc.queryForObject(
            "SELECT id FROM spaces WHERE owner_id = ? AND is_default", UUID.class, person);
    jdbc.update(
        "INSERT INTO chats (id, space_id, author_id, organization_id, use_knowledge, status,"
            + " created_at, updated_at) VALUES (?, ?, ?, ?, true, 'PRIVATE', now(), now())",
        UUID.randomUUID(),
        personalSpace,
        person,
        Organization.DEFAULT_ID);
    jdbc.update(
        "INSERT INTO chat_cleanup_holds (user_id, organization_id, held_at) VALUES (?, ?, now())",
        person,
        Organization.DEFAULT_ID);

    refused(person, "CHAT_CLEANUP_HELD");
    assertThat(count("SELECT count(*) FROM chats WHERE author_id = ?", person)).isEqualTo(1);
  }

  private void refused(UUID person, String code) throws Exception {
    mockMvc
        .perform(post(ERASURE.formatted(person)).with(dev("dev-admin")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(code));
    User kept = users.findById(person).orElseThrow();
    assertThat(kept.isErased()).isFalse();
    assertThat(kept.getDisplayName()).isEqualTo(marker + " Maria");
  }

  private UUID providerAccount(String issuer, SystemRole role) {
    User user =
        new User(
            UUID.randomUUID().toString(),
            issuer,
            marker + "-maria@stadt.example",
            marker + " Maria");
    user.setOrganizationId(Organization.DEFAULT_ID);
    user.setSystemRole(role);
    UUID id = users.save(user).getId();
    createdUsers.add(id);
    return id;
  }

  private UUID privateLibraryOf(UUID owner) {
    UUID library = UUID.randomUUID();
    jdbc.update(
        "WITH shell AS (INSERT INTO assets (id, asset_type, organization_id, name, owner_type,"
            + " owner_user_id, owner_only) VALUES (?, 'KNOWLEDGE_LIBRARY', ?, 'Privat', 'USER', ?,"
            + " true) RETURNING id, organization_id) INSERT INTO knowledge_libraries (id,"
            + " organization_id, source_type) SELECT id, organization_id, 'UPLOAD' FROM shell",
        library,
        Organization.DEFAULT_ID,
        owner);
    createdLibraries.add(library);
    return library;
  }

  private long count(String sql, Object argument) {
    Long result = jdbc.queryForObject(sql, Long.class, argument);
    return result == null ? 0 : result;
  }

  private static RequestPostProcessor dev(String devUser) {
    return request -> {
      request.addHeader(DevAuthFilter.DEV_USER_HEADER, devUser);
      return request;
    };
  }
}
