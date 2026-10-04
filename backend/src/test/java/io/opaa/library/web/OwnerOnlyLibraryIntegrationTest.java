package io.opaa.library.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.asset.AssetShellService;
import io.opaa.asset.AssetSuccessionSource;
import io.opaa.auth.DevAuthFilter;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.SourceType;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Nur-Besitzerin-Regel over HTTP: a private library is created through the shell's own path,
 * the one the creation of private libraries uses. Sharing, handing on and releasing it fail - for
 * its owner with {@code 403 OWNER_ONLY_ASSET}, for the system administration with the {@code 404}
 * of a library it does not know, and with {@code 403} again where the administrator owns it. The
 * owner reads, manages and deletes it as before; bulk transfer and succession leave it out.
 */
@OpaaIntegrationTest
class OwnerOnlyLibraryIntegrationTest {

  private static final String OWNER_ONLY_ASSET = "OWNER_ONLY_ASSET";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private OwnLibraryFixtures ownLibraryFixtures;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private AssetShellService shellService;
  @Autowired private AssetSuccessionSource successionSource;
  @Autowired private TransactionTemplate transactionTemplate;

  private final List<UUID> libraryIds = new ArrayList<>();
  private final List<UUID> extraUserIds = new ArrayList<>();
  private UUID ownerId;
  private UUID adminId;

  @BeforeEach
  void provisionCallers() throws Exception {
    mockMvc.perform(get("/api/v1/spaces").with(devUser())).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/spaces").with(devAdmin())).andExpect(status().isOk());
    ownerId = userIdOf("dev-user@opaa.local");
    adminId = userIdOf("admin@opaa.local");
  }

  @AfterEach
  void removeOwnRows() {
    for (UUID libraryId : libraryIds) {
      jdbcTemplate.update("DELETE FROM audit_log WHERE object_id = ?", libraryId.toString());
      jdbcTemplate.update("DELETE FROM asset_grant_history WHERE asset_id = ?", libraryId);
      jdbcTemplate.update("DELETE FROM permission_transfer_objects WHERE asset_id = ?", libraryId);
    }
    ownLibraryFixtures.removeLibraries(libraryIds.toArray(new UUID[0]));
    libraryIds.clear();
    for (UUID userId : extraUserIds) {
      jdbcTemplate.update("DELETE FROM permission_transfers WHERE source_user_id = ?", userId);
      jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }
    extraUserIds.clear();
  }

  @Test
  void theOwnerReadsManagesAndDeletesHerPrivateLibrary() throws Exception {
    UUID library = privateLibraryOf(ownerId);

    mockMvc
        .perform(get("/api/v1/libraries/" + library).with(devUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.myRole").value("OWNER"));
    mockMvc
        .perform(get("/api/v1/libraries").with(devUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.id == '" + library + "')]").exists());
    mockMvc
        .perform(
            put("/api/v1/libraries/" + library)
                .with(devUser())
                .content("{\"name\":\"Meine Ablage\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Meine Ablage"));
    mockMvc
        .perform(get("/api/v1/assets/KNOWLEDGE_LIBRARY/" + library + "/grants").with(devUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));

    mockMvc
        .perform(delete("/api/v1/libraries/" + library).with(devUser()))
        .andExpect(status().isNoContent());
    assertThat(countWhere("assets", library)).isZero();
  }

  @Test
  void theOwnerCanNeitherShareNorHandOnNorReleaseIt() throws Exception {
    UUID library = privateLibraryOf(ownerId);

    refusedByTheRule(grant(library, devUser(), "USER", adminId));
    refusedByTheRule(grant(library, devUser(), "GROUP", UUID.randomUUID()));
    refusedByTheRule(grant(library, devUser(), "ALL_ACCOUNTS", null));
    refusedByTheRule(transfer(library, devUser(), adminId));
    refusedByTheRule(release(library, devUser()));

    assertThat(grantCount(library)).isEqualTo(1);
    assertThat(ownerOf(library)).isEqualTo(ownerId);
    assertThat(externalAccessStateOf(library)).isEqualTo("NEVER_SET");
  }

  /** Every path the administrative floor used to open answers like an unknown library. */
  @Test
  void theSystemAdministrationDoesNotSeeAnotherPersonsPrivateLibrary() throws Exception {
    UUID library = privateLibraryOf(ownerId);

    unknown(mockMvc.perform(get("/api/v1/libraries/" + library).with(devAdmin())));
    unknown(
        mockMvc.perform(
            get("/api/v1/assets/KNOWLEDGE_LIBRARY/" + library + "/grants").with(devAdmin())));
    unknown(grant(library, devAdmin(), "USER", adminId));
    unknown(grant(library, devAdmin(), "ALL_ACCOUNTS", null));
    unknown(transfer(library, devAdmin(), adminId));
    unknown(release(library, devAdmin()));
    unknown(shareCap(library, false));
    unknown(mockMvc.perform(delete("/api/v1/libraries/" + library).with(devAdmin())));
    mockMvc
        .perform(
            get("/api/v1/assets/KNOWLEDGE_LIBRARY/" + library + "/access-derivation")
                .with(devAdmin()))
        .andExpect(status().isNotFound());

    assertThat(grantCount(library)).isEqualTo(1);
    assertThat(ownerOf(library)).isEqualTo(ownerId);
  }

  /** The administration cannot loosen the rule even on a library it owns itself. */
  @Test
  void anAdministratorOwningAPrivateLibraryIsBoundByTheRuleToo() throws Exception {
    UUID library = privateLibraryOf(adminId);

    refusedByTheRule(grant(library, devAdmin(), "USER", ownerId));
    refusedByTheRule(grant(library, devAdmin(), "ALL_ACCOUNTS", null));
    refusedByTheRule(transfer(library, devAdmin(), ownerId));
    refusedByTheRule(release(library, devAdmin()));
    refusedByTheRule(shareCap(library, true));

    assertThat(grantCount(library)).isEqualTo(1);
    mockMvc
        .perform(get("/api/v1/libraries/" + library).with(devUser()))
        .andExpect(status().isNotFound());
  }

  @Test
  void aBulkTransferNeitherCountsNorMovesAPrivateLibrary() throws Exception {
    UUID person = insertUser("bulk");
    UUID privateLibrary = privateLibraryOf(person);
    UUID sharedLibrary = sharedLibraryOf(person);

    String preview =
        mockMvc
            .perform(
                post("/api/v1/permission-transfers/preview")
                    .with(devAdmin())
                    .content(ownershipOrder(person, ownerId, null)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.counts.ownedAssets").value(1))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    mockMvc
        .perform(
            post("/api/v1/permission-transfers")
                .with(devAdmin())
                .content(ownershipOrder(person, ownerId, JsonPath.read(preview, "$.previewId"))))
        .andExpect(status().isCreated());

    assertThat(ownerOf(sharedLibrary)).isEqualTo(ownerId);
    assertThat(ownerOf(privateLibrary)).isEqualTo(person);
  }

  @Test
  void aPrivateLibraryOfAnInactiveOwnerHasNoSuccession() {
    UUID person = insertUser("succession");
    UUID privateLibrary = privateLibraryOf(person);
    UUID sharedLibrary = sharedLibraryOf(person);
    jdbcTemplate.update("UPDATE users SET directory_locked_at = now() WHERE id = ?", person);

    assertThat(successionSource.findingFor(sharedLibrary)).isPresent();
    assertThat(successionSource.findingFor(privateLibrary)).isEmpty();
    assertThat(successionSource.findingsOf(Organization.DEFAULT_ID))
        .noneSatisfy(finding -> assertThat(finding.objectId()).isEqualTo(privateLibrary));
  }

  // -------------------------------------------------------------------------------------------
  // Fixture
  // -------------------------------------------------------------------------------------------

  /** The creation path private libraries take: the owner-only entity and the shell's register. */
  private UUID privateLibraryOf(UUID owner) {
    return create(
        KnowledgeLibrary.ownerOnly(
            Organization.DEFAULT_ID,
            "Privat " + UUID.randomUUID(),
            null,
            owner,
            SourceType.UPLOAD,
            null,
            null,
            null,
            null,
            false),
        owner);
  }

  private UUID sharedLibraryOf(UUID owner) {
    return create(
        KnowledgeLibrary.ownedByUser(
            Organization.DEFAULT_ID, "Geteilt " + UUID.randomUUID(), null, owner),
        owner);
  }

  private UUID create(KnowledgeLibrary library, UUID creator) {
    UUID id =
        transactionTemplate.execute(
            status -> {
              KnowledgeLibrary saved = libraryRepository.save(library);
              shellService.registerCreated(
                  saved, creator, Map.of("name", saved.getName(), "sourceType", "UPLOAD"));
              return saved.getId();
            });
    libraryIds.add(id);
    return id;
  }

  private UUID insertUser(String label) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, organization_id)"
            + " VALUES (?, ?, 'owner-only-it', ?, ?, ?)",
        id,
        label + "-" + id,
        label + "-" + id + "@example.com",
        "Person " + label,
        Organization.DEFAULT_ID);
    extraUserIds.add(id);
    return id;
  }

  private static String ownershipOrder(UUID source, UUID target, String previewId) {
    String confirmation =
        previewId == null ? "" : ",\"confirmed\":true,\"previewId\":\"" + previewId + "\"";
    return """
        {"sourceType":"USER","sourceId":"%s","targetType":"USER","targetId":"%s",\
        "scope":["OWNERSHIP"]%s}"""
        .formatted(source, target, confirmation);
  }

  private ResultActions grant(
      UUID library, RequestPostProcessor caller, String subjectType, UUID subjectId)
      throws Exception {
    String subject = subjectId == null ? "" : ",\"subjectId\":\"" + subjectId + "\"";
    return mockMvc.perform(
        post("/api/v1/assets/KNOWLEDGE_LIBRARY/" + library + "/grants")
            .with(caller)
            .content(
                "{\"subjectType\":\"" + subjectType + "\",\"role\":\"VIEWER\"" + subject + "}"));
  }

  private ResultActions transfer(UUID library, RequestPostProcessor caller, UUID newOwner)
      throws Exception {
    return mockMvc.perform(
        post("/api/v1/assets/KNOWLEDGE_LIBRARY/" + library + "/transfer-ownership")
            .with(caller)
            .content("{\"ownerType\":\"USER\",\"ownerId\":\"" + newOwner + "\"}"));
  }

  private ResultActions release(UUID library, RequestPostProcessor caller) throws Exception {
    Instant expiresAt = Instant.now().plus(30, ChronoUnit.DAYS);
    return mockMvc.perform(
        put("/api/v1/libraries/" + library + "/external-access")
            .with(caller)
            .content("{\"enabled\":true,\"expiresAt\":\"" + expiresAt + "\"}"));
  }

  private ResultActions shareCap(UUID library, boolean allowed) throws Exception {
    MockHttpServletRequestBuilder request =
        put("/api/v1/libraries/" + library + "/share-cap")
            .with(devAdmin())
            .content("{\"allAccountsGrantAllowed\":" + allowed + "}");
    return mockMvc.perform(request);
  }

  private static void refusedByTheRule(ResultActions result) throws Exception {
    result.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(OWNER_ONLY_ASSET));
  }

  private static void unknown(ResultActions result) throws Exception {
    result.andExpect(status().isNotFound());
  }

  private UUID userIdOf(String email) {
    return jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
  }

  private UUID ownerOf(UUID library) {
    return jdbcTemplate.queryForObject(
        "SELECT owner_user_id FROM assets WHERE id = ?", UUID.class, library);
  }

  private int grantCount(UUID library) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM asset_grants WHERE asset_id = ?", Integer.class, library);
  }

  private String externalAccessStateOf(UUID library) {
    return jdbcTemplate.queryForObject(
        "SELECT external_access_state FROM knowledge_libraries WHERE id = ?",
        String.class,
        library);
  }

  private int countWhere(String table, UUID id) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM " + table + " WHERE id = ?", Integer.class, id);
  }

  private RequestPostProcessor devUser() {
    return request -> {
      request.addHeader(DevAuthFilter.DEV_USER_HEADER, "dev-user");
      request.setContentType(MediaType.APPLICATION_JSON_VALUE);
      return request;
    };
  }

  private RequestPostProcessor devAdmin() {
    return request -> {
      request.setContentType(MediaType.APPLICATION_JSON_VALUE);
      return request;
    };
  }
}
