package io.opaa.library.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.asset.AssetShellService;
import io.opaa.auth.DevAuthFilter;
import io.opaa.indexing.document.DocumentIngest;
import io.opaa.indexing.document.DocumentIngestResult;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.indexing.job.PersonalQuotaExhaustedException;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.SourceType;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * The storage quota across all private libraries of a person against the Liquibase schema: a second
 * private library does not open more room, concurrent intake into several of them never stores past
 * the limit, the person reads her own use, and the administration sets the house-wide value and
 * sees no person's use.
 */
@OpaaIntegrationTest
class PrivateStorageQuotaIntegrationTest {

  private static final long QUOTA = 1000;

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private OwnLibraryFixtures ownLibraryFixtures;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private AssetShellService shellService;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DocumentIngestService documentIngestService;

  private final List<UUID> libraryIds = new ArrayList<>();
  private UUID ownerId;

  @BeforeEach
  void provisionCallersAndQuota() throws Exception {
    mockMvc.perform(get("/api/v1/spaces").with(devUser())).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/spaces").with(devAdmin())).andExpect(status().isOk());
    ownerId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM users WHERE email = 'dev-user@opaa.local'", UUID.class);
    setQuota("{\"quotaBytes\":" + QUOTA + "}");
  }

  @AfterEach
  void removeOwnRows() {
    for (UUID libraryId : libraryIds) {
      jdbcTemplate.update("DELETE FROM audit_log WHERE object_id = ?", libraryId.toString());
      jdbcTemplate.update("DELETE FROM asset_grant_history WHERE asset_id = ?", libraryId);
    }
    ownLibraryFixtures.removeLibraries(libraryIds.toArray(new UUID[0]));
    libraryIds.clear();
    jdbcTemplate.update("DELETE FROM audit_log WHERE event_type = 'PRIVATE_STORAGE_QUOTA_CHANGED'");
  }

  @Test
  void aSecondPrivateLibraryDoesNotOpenMoreRoom() throws Exception {
    KnowledgeLibrary first = privateLibraryOf(ownerId);
    KnowledgeLibrary second = privateLibraryOf(ownerId);
    KnowledgeLibrary shared = sharedLibraryOf(ownerId);

    assertThat(ingest(first, "erste", 600)).isEqualTo(DocumentIngestResult.PROCESSED);
    assertThatThrownBy(() -> ingest(second, "zweite", 600))
        .isInstanceOf(PersonalQuotaExhaustedException.class)
        .hasMessageStartingWith("Speicherkontingent Ihrer privaten Bibliotheken erschöpft");
    assertThat(documentsOf(second)).isZero();
    assertThat(ingest(shared, "geteilt", 600)).isEqualTo(DocumentIngestResult.PROCESSED);
    assertThat(ingest(second, "passt", 400)).isEqualTo(DocumentIngestResult.PROCESSED);

    mockMvc
        .perform(get("/api/v1/me/private-storage").with(devUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.usedBytes").value(1000))
        .andExpect(jsonPath("$.quotaBytes").value(QUOTA));
    mockMvc
        .perform(get("/api/v1/me/private-storage").with(devAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.usedBytes").value(0));
  }

  /**
   * Four runs into four private libraries of one person at once, each trying more than the whole
   * quota: what is stored together ends exactly at the limit, never past it.
   */
  @Test
  void concurrentIntakeIntoSeveralPrivateLibrariesStopsAtTheLimit() throws Exception {
    List<KnowledgeLibrary> libraries = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      libraries.add(privateLibraryOf(ownerId));
    }
    ExecutorService pool = Executors.newFixedThreadPool(libraries.size());
    try {
      List<Future<Integer>> runs = new ArrayList<>();
      for (KnowledgeLibrary library : libraries) {
        runs.add(pool.submit(runOf(library, 12)));
      }
      int stored = 0;
      for (Future<Integer> run : runs) {
        stored += run.get(2, TimeUnit.MINUTES);
      }
      assertThat(stored).isEqualTo(10);
    } finally {
      pool.shutdownNow();
    }

    assertThat(storedBytesOf(libraries)).isEqualTo(QUOTA);
  }

  @Test
  void theSystemAdministrationSetsTheHouseWideValue() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/private-libraries/quota").with(devAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.quotaBytes").value(QUOTA))
        .andExpect(jsonPath("$.overridden").value(true))
        .andExpect(jsonPath("$.defaultQuotaBytes").value(10737418240L));
    mockMvc
        .perform(put("/api/v1/admin/private-libraries/quota").with(devAdmin()).content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.quotaBytes").value(10737418240L))
        .andExpect(jsonPath("$.overridden").value(false));
    mockMvc
        .perform(
            put("/api/v1/admin/private-libraries/quota")
                .with(devAdmin())
                .content("{\"quotaBytes\":-1}"))
        .andExpect(status().isBadRequest());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log WHERE event_type = 'PRIVATE_STORAGE_QUOTA_CHANGED'",
                Integer.class))
        .isEqualTo(2);
  }

  @Test
  void onlyTheSystemAdministrationReachesTheQuotaAndTheSums() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/private-libraries/quota").with(devUser()))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            put("/api/v1/admin/private-libraries/quota")
                .with(devUser())
                .content("{\"quotaBytes\":0}"))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(get("/api/v1/admin/private-libraries/summary").with(devUser()))
        .andExpect(status().isForbidden());
  }

  /** One person with private libraries is fewer than the minimum: no sum, no name, no id. */
  @Test
  void theSumsOfFewPersonsAreNotTold() throws Exception {
    KnowledgeLibrary library = privateLibraryOf(ownerId);
    ingest(library, "summe", 300);

    String answer =
        mockMvc
            .perform(get("/api/v1/admin/private-libraries/summary").with(devAdmin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.quotaBytes").value(QUOTA))
            .andExpect(jsonPath("$.owners.fewerThanPersons").value(5))
            .andExpect(jsonPath("$.usedBytes.fewerThanPersons").value(5))
            .andExpect(jsonPath("$.runWindowDays").value(30))
            .andExpect(jsonPath("$.runEnds[?(@.category == 'QUOTA_EXHAUSTED')]").exists())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);

    assertThat(answer)
        .doesNotContain(ownerId.toString())
        .doesNotContain(library.getId().toString())
        .doesNotContain("\"value\":300");
  }

  // -------------------------------------------------------------------------------------------
  // Fixture
  // -------------------------------------------------------------------------------------------

  private Callable<Integer> runOf(KnowledgeLibrary library, int attempts) {
    return () -> {
      int stored = 0;
      for (int i = 0; i < attempts; i++) {
        try {
          ingest(library, library.getId() + "-" + i, 100);
          stored++;
        } catch (PersonalQuotaExhaustedException ended) {
          return stored;
        }
      }
      return stored;
    };
  }

  /** Text of exactly {@code bytes} bytes under a path of its own. */
  private DocumentIngestResult ingest(KnowledgeLibrary library, String key, int bytes)
      throws Exception {
    String body = ("<p>" + key + "</p>");
    String text = body + "x".repeat(bytes - body.length());
    assertThat(text.getBytes(StandardCharsets.UTF_8)).hasSize(bytes);
    return documentIngestService.ingest(
        DocumentIngest.text(library, "quota-it/" + key, text).sourceType(SourceType.UPLOAD).build(),
        null);
  }

  private int documentsOf(KnowledgeLibrary library) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM documents WHERE library_id = ?", Integer.class, library.getId());
  }

  private long storedBytesOf(List<KnowledgeLibrary> libraries) {
    long bytes = 0;
    for (KnowledgeLibrary library : libraries) {
      bytes +=
          jdbcTemplate.queryForObject(
              "SELECT coalesce(sum(file_size), 0) FROM documents WHERE library_id = ?",
              Long.class,
              library.getId());
    }
    return bytes;
  }

  private void setQuota(String body) throws Exception {
    mockMvc
        .perform(put("/api/v1/admin/private-libraries/quota").with(devAdmin()).content(body))
        .andExpect(status().isOk());
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
            null,
            null,
            false),
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
