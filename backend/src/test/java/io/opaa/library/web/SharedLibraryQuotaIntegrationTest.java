package io.opaa.library.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.asset.AssetShellService;
import io.opaa.auth.DevAuthFilter;
import io.opaa.indexing.document.DocumentIngest;
import io.opaa.indexing.document.DocumentIngestResult;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.LibraryProperties;
import io.opaa.knowledge.SourceType;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import java.nio.charset.StandardCharsets;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The house-wide storage quota per shared library against the Flyway schema: the administration
 * sets it within the operator's upper limit, it applies to the next intake without a restart, and a
 * library already above it keeps every document and only takes in nothing that grows it.
 */
@OpaaIntegrationTest
class SharedLibraryQuotaIntegrationTest {

  private static final String QUOTA = "/api/v1/admin/shared-libraries/quota";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private OwnLibraryFixtures ownLibraryFixtures;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private AssetShellService shellService;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DocumentIngestService documentIngestService;
  @Autowired private LibraryProperties libraryProperties;

  private final List<UUID> libraryIds = new ArrayList<>();
  private UUID ownerId;

  @BeforeEach
  void provisionCallers() throws Exception {
    mockMvc.perform(get("/api/v1/spaces").with(devUser())).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/spaces").with(devAdmin())).andExpect(status().isOk());
    ownerId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM users WHERE email = 'dev-user@opaa.local'", UUID.class);
  }

  @AfterEach
  void removeOwnRows() {
    jdbcTemplate.update("DELETE FROM shared_library_quota_settings");
    for (UUID libraryId : libraryIds) {
      jdbcTemplate.update("DELETE FROM audit_log WHERE object_id = ?", libraryId.toString());
      jdbcTemplate.update("DELETE FROM asset_grant_history WHERE asset_id = ?", libraryId);
    }
    ownLibraryFixtures.removeLibraries(libraryIds.toArray(new UUID[0]));
    libraryIds.clear();
    jdbcTemplate.update("DELETE FROM audit_log WHERE event_type = 'SHARED_LIBRARY_QUOTA_CHANGED'");
  }

  @Test
  void theSystemAdministrationSetsTheHouseWideValueWithinTheUpperLimit() throws Exception {
    long cap = libraryProperties.quotaBytes();
    mockMvc
        .perform(get(QUOTA).with(devAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.quotaBytes").value(cap))
        .andExpect(jsonPath("$.capBytes").value(cap))
        .andExpect(jsonPath("$.ownQuotaBytes").doesNotExist());

    mockMvc
        .perform(put(QUOTA).with(devAdmin()).content("{\"quotaBytes\":2048}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.quotaBytes").value(2048))
        .andExpect(jsonPath("$.ownQuotaBytes").value(2048));
    mockMvc
        .perform(put(QUOTA).with(devAdmin()).content("{\"quotaBytes\":2048}"))
        .andExpect(status().isOk());
    mockMvc
        .perform(put(QUOTA).with(devAdmin()).content("{\"quotaBytes\":" + (cap + 1) + "}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("quotaBytes"));
    mockMvc
        .perform(put(QUOTA).with(devAdmin()).content("{\"quotaBytes\":0}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(put(QUOTA).with(devAdmin()).content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.quotaBytes").value(cap))
        .andExpect(jsonPath("$.ownQuotaBytes").doesNotExist());

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log WHERE event_type = 'SHARED_LIBRARY_QUOTA_CHANGED'",
                Integer.class))
        .isEqualTo(2);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT after FROM audit_log WHERE event_type = 'SHARED_LIBRARY_QUOTA_CHANGED'"
                    + " ORDER BY recorded_at DESC LIMIT 1",
                String.class))
        .contains("\"quotaBytes\":" + cap);
  }

  @Test
  void onlyTheSystemAdministrationReachesTheQuota() throws Exception {
    mockMvc.perform(get(QUOTA).with(devUser())).andExpect(status().isForbidden());
    mockMvc
        .perform(put(QUOTA).with(devUser()).content("{\"quotaBytes\":2048}"))
        .andExpect(status().isForbidden());
  }

  /**
   * Lowering the value below what a library holds deletes nothing: the library keeps its document,
   * takes in nothing that grows it, still replaces a changed document of the same size, and its
   * managers see the quota in force against its use. A private library stays outside the value.
   */
  @Test
  void aLibraryAboveTheLoweredValueKeepsItsDocumentsAndTakesInNothingMore() throws Exception {
    KnowledgeLibrary shared = sharedLibraryOf(ownerId);
    KnowledgeLibrary privateLibrary = privateLibraryOf(ownerId);
    assertThat(ingest(shared, "bestand", 'a', 600)).isEqualTo(DocumentIngestResult.PROCESSED);

    mockMvc
        .perform(put(QUOTA).with(devAdmin()).content("{\"quotaBytes\":500}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.librariesOverQuota").value(org.hamcrest.Matchers.greaterThan(0)));

    assertThat(ingest(shared, "neu", 'b', 10)).isEqualTo(DocumentIngestResult.QUOTA_EXCEEDED);
    assertThat(ingest(shared, "bestand", 'c', 600)).isEqualTo(DocumentIngestResult.PROCESSED);
    assertThat(documentsOf(shared)).isEqualTo(1);
    assertThat(ingest(privateLibrary, "privat", 'd', 600))
        .isEqualTo(DocumentIngestResult.PROCESSED);
    mockMvc
        .perform(get("/api/v1/libraries/" + shared.getId()).with(devUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.storageQuotaBytes").value(500))
        .andExpect(jsonPath("$.storageUsedBytes").value(600));

    mockMvc.perform(put(QUOTA).with(devAdmin()).content("{}")).andExpect(status().isOk());
    assertThat(ingest(shared, "neu", 'b', 10)).isEqualTo(DocumentIngestResult.PROCESSED);
  }

  // -------------------------------------------------------------------------------------------
  // Fixture
  // -------------------------------------------------------------------------------------------

  /**
   * Text of exactly {@code bytes} bytes under a path of its own, its content set by {@code fill}.
   */
  private DocumentIngestResult ingest(KnowledgeLibrary library, String key, char fill, int bytes)
      throws Exception {
    String body = "<p>" + key + "</p>";
    String text = body + String.valueOf(fill).repeat(bytes - body.length());
    assertThat(text.getBytes(StandardCharsets.UTF_8)).hasSize(bytes);
    return documentIngestService.ingest(
        DocumentIngest.text(library, "shared-quota-it/" + key, text)
            .sourceType(SourceType.UPLOAD)
            .build(),
        null);
  }

  private int documentsOf(KnowledgeLibrary library) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM documents WHERE library_id = ?", Integer.class, library.getId());
  }

  private KnowledgeLibrary privateLibraryOf(UUID owner) {
    return create(
        KnowledgeLibrary.ownerOnly(
            Organization.DEFAULT_ID,
            "Privat " + UUID.randomUUID(),
            null,
            owner,
            SourceType.UPLOAD,
            null,
            null,
            null),
        owner);
  }

  private KnowledgeLibrary sharedLibraryOf(UUID owner) {
    return create(
        KnowledgeLibrary.ownedByUser(
            Organization.DEFAULT_ID, "Geteilt " + UUID.randomUUID(), null, owner),
        owner);
  }

  private KnowledgeLibrary create(KnowledgeLibrary library, UUID creator) {
    KnowledgeLibrary saved =
        transactionTemplate.execute(
            status -> {
              KnowledgeLibrary stored = libraryRepository.save(library);
              shellService.registerCreated(
                  stored, creator, Map.of("name", stored.getName(), "sourceType", "UPLOAD"));
              return stored;
            });
    libraryIds.add(saved.getId());
    return saved;
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
