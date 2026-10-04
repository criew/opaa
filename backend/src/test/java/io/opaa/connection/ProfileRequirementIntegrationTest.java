package io.opaa.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.auth.DevAuthFilter;
import io.opaa.chat.ChatSource;
import io.opaa.indexing.source.DocumentIndexingService;
import io.opaa.indexing.source.profileprobe.ProfileProbeSourceConnector;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.SourceType;
import io.opaa.metadata.MetadataFilter;
import io.opaa.organization.Organization;
import io.opaa.query.citation.ChatSourceAssembler;
import io.opaa.test.ConnectorReleases;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The profile requirement of a connector type through the API (spec "Profilpflicht"): it needs a
 * profile that could take over, refuses every own address, and leaves the libraries with their own
 * address running or locked until they are connected through a profile.
 */
@OpaaIntegrationTest
class ProfileRequirementIntegrationTest {

  private static final String TYPE = "PROFILE_PROBE";
  private static final String PROFILES = "/api/v1/admin/connection-profiles";
  private static final String REQUIREMENT =
      "/api/v1/admin/connector-types/" + TYPE + "/profile-requirement";
  private static final String SERVER = "https://probe.example.org";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OwnLibraryFixtures libraryFixtures;
  @Autowired private DocumentIndexingService indexingService;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private ChatSourceAssembler sourceAssembler;

  private final List<UUID> profiles = new ArrayList<>();
  private final List<UUID> libraries = new ArrayList<>();

  @BeforeEach
  void clearThePolicy() {
    jdbc.update("DELETE FROM connector_type_policies WHERE source_type = ?", TYPE);
  }

  @AfterEach
  void tearDown() {
    libraryFixtures.removeLibraries(libraries.toArray(UUID[]::new));
    for (UUID profile : profiles) {
      ConnectorReleases.withdraw(jdbc, "PROFILE:" + profile);
      jdbc.update("DELETE FROM connection_profiles WHERE id = ?", profile);
    }
    jdbc.update("DELETE FROM connector_type_policies WHERE source_type = ?", TYPE);
  }

  /** Acceptance criterion: the switch needs an unlocked profile that admits libraries. */
  @Test
  void switchingOnNeedsAProfileThatCouldTakeOver() throws Exception {
    mockMvc
        .perform(as("dev-admin", get(REQUIREMENT)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.switchable").value(false))
        .andExpect(jsonPath("$.notSwitchableReason").value(Matchers.containsString("Zugang")))
        .andExpect(jsonPath("$.state.profileSupport").value("OPTIONAL"))
        .andExpect(jsonPath("$.state.profileRequired").value(false))
        .andExpect(jsonPath("$.coverageNotice").value(ProfileProbeSourceConnector.GAP));
    mockMvc
        .perform(require("LOCKED"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PROFILE_REQUIREMENT_NEEDS_PROFILE"));

    UUID locked = createProfile("Zugang gesperrt", "LIBRARY");
    lockProfile(locked);
    createProfile("Zugang nur Personen", "PERSON");
    mockMvc
        .perform(require("LOCKED"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PROFILE_REQUIREMENT_NEEDS_PROFILE"));

    createProfile("Zugang Bibliotheken", "LIBRARY");
    mockMvc
        .perform(as("dev-admin", get(REQUIREMENT)))
        .andExpect(jsonPath("$.switchable").value(true))
        .andExpect(jsonPath("$.notSwitchableReason").doesNotExist());
    mockMvc
        .perform(require("LOCKED"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.profileRequired").value(true))
        .andExpect(jsonPath("$.ownAddressStock").value("LOCKED"))
        .andExpect(jsonPath("$.profileRequiredAt").exists());
    mockMvc
        .perform(as("dev-admin", get("/api/v1/admin/connector-types")))
        .andExpect(jsonPath("$[?(@.sourceType == '" + TYPE + "')].profileRequired").value(true));
  }

  @Test
  void aTypeWithoutOptionalProfilesIsNotSwitchedAndTheRequestMustFit() throws Exception {
    mockMvc
        .perform(
            as("dev-admin", put("/api/v1/admin/connector-types/FILESYSTEM/profile-requirement"))
                .content("{\"required\": true, \"ownAddressStock\": \"RUNS\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.error").value(Matchers.containsString("nicht über Zugänge verbunden")));
    mockMvc
        .perform(
            as("dev-admin", get("/api/v1/admin/connector-types/FILESYSTEM/profile-requirement")))
        .andExpect(jsonPath("$.switchable").value(false))
        .andExpect(jsonPath("$.coverageNotice").doesNotExist());
    mockMvc
        .perform(
            as("dev-admin", put("/api/v1/admin/connector-types/UPLOAD/profile-requirement"))
                .content("{\"required\": false}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(Matchers.containsString("keine Profilpflicht")));
    mockMvc
        .perform(
            as(
                    "dev-admin",
                    put("/api/v1/admin/connector-types/PROFILE_OAUTH_PROBE/profile-requirement"))
                .content("{\"required\": true, \"ownAddressStock\": \"RUNS\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(Matchers.containsString("ohnehin nur über Zugänge")));
    mockMvc
        .perform(as("dev-admin", get("/api/v1/admin/connector-types")))
        .andExpect(
            jsonPath("$[?(@.sourceType == 'PROFILE_OAUTH_PROBE')].profileRequired").value(true))
        .andExpect(
            jsonPath("$[?(@.sourceType == 'PROFILE_OAUTH_PROBE')].profileSupport")
                .value("REQUIRED"));
    mockMvc
        .perform(as("dev-admin", put(REQUIREMENT)).content("{\"required\": true}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            as("dev-admin", put(REQUIREMENT))
                .content("{\"required\": false, \"ownAddressStock\": \"RUNS\"}"))
        .andExpect(status().isBadRequest());
    for (MockHttpServletRequestBuilder request :
        List.of(
            as("dev-user", get(REQUIREMENT)),
            as("dev-user", put(REQUIREMENT))
                .content("{\"required\": true, \"ownAddressStock\": \"RUNS\"}"))) {
      mockMvc.perform(request).andExpect(status().isForbidden());
    }
  }

  /**
   * Acceptance criteria: with the stock running on, a library with its own address runs and keeps
   * its address - a new one only through a profile - while secret, scope and rhythm stay
   * changeable. Every other own address is refused with 400 PROFILE_REQUIRED.
   */
  @Test
  void runningOnKeepsTheAddressAndRefusesEveryOtherOwnAddress() throws Exception {
    UUID profile = createProfile("Zugang Pflicht", "LIBRARY");
    UUID own = createOwnLibrary(SERVER + "/eigen");
    UUID connected = createLibrary(profile);
    mockMvc.perform(require("RUNS")).andExpect(status().isOk());

    mockMvc
        .perform(as("dev-admin", get(REQUIREMENT)))
        .andExpect(jsonPath("$.ownAddressLibraries[*].id").value(Matchers.hasItem(own.toString())))
        .andExpect(
            jsonPath("$.ownAddressLibraries[*].id")
                .value(Matchers.not(Matchers.hasItem(connected.toString()))))
        .andExpect(
            jsonPath("$.ownAddressLibraries[?(@.id == '" + own + "')].ownerType")
                .value(Matchers.hasItem("USER")));
    run(own, "COMPLETED");
    mockMvc
        .perform(as("dev-admin", get("/api/v1/libraries/" + own)))
        .andExpect(jsonPath("$.sourceBlock").doesNotExist());

    expectProfileRequired(update(own, SERVER + "/anders", null));
    mockMvc
        .perform(update(own, SERVER + "/eigen", "nutzer:neu"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sourceUrl").value(SERVER + "/eigen"));

    expectProfileRequired(
        as("dev-admin", post("/api/v1/libraries"))
            .content(
                "{\"name\": \"Eigen "
                    + UUID.randomUUID()
                    + "\", \"sourceType\": \""
                    + TYPE
                    + "\", \"sourceUrl\": \""
                    + SERVER
                    + "/neu\"}"));
    expectProfileRequired(
        as("dev-admin", post("/api/v1/libraries/source-test"))
            .content("{\"sourceType\": \"" + TYPE + "\", \"sourceUrl\": \"" + SERVER + "\"}"));
    expectProfileRequired(
        as("dev-admin", post("/api/v1/source-types/" + TYPE + "/browse"))
            .content("{\"sourceUrl\": \"" + SERVER + "\"}"));
    expectProfileRequired(
        as("dev-admin", delete("/api/v1/libraries/" + connected + "/connection-profile")));
    mockMvc
        .perform(as("dev-admin", get("/api/v1/source-types")))
        .andExpect(jsonPath("$[?(@.type == '" + TYPE + "')].profileRequired").value(true))
        .andExpect(jsonPath("$[?(@.type == '" + TYPE + "')].creatableWithOwnAddress").value(false))
        .andExpect(jsonPath("$[?(@.type == '" + TYPE + "')].creatable").value(true))
        .andExpect(jsonPath("$[?(@.type == 'RSS_FEED')].profileRequired").value(false));
    createLibrary(profile);
  }

  /**
   * Acceptance criteria: with the stock locked, a library with its own address runs no more and
   * says why, an answer shows its "Stand vom", and connecting it through a profile - or switching
   * the requirement off - lets it run again. Switching is a governance event.
   */
  @Test
  void aLockedStockRunsNoMoreUntilConnectedOrSwitchedOff() throws Exception {
    UUID profile = createProfile("Zugang Sperre", "LIBRARY");
    UUID repaired = createOwnLibrary(SERVER + "/eins");
    UUID waiting = createOwnLibrary(SERVER + "/zwei");
    UUID document = document(repaired);
    run(repaired, "COMPLETED");
    mockMvc.perform(require("LOCKED")).andExpect(status().isOk());

    run(repaired, "FAILED");
    assertThat(lastRunMessage(repaired))
        .contains("Gesperrt – Inhalt wird nicht mehr aktualisiert")
        .contains("nur noch über Zugänge nutzbar");
    assertThat(
            indexingService.triggerScheduledIndexing(
                libraryRepository.findById(waiting).orElseThrow()))
        .isNull();
    mockMvc
        .perform(as("dev-admin", get("/api/v1/libraries/" + repaired)))
        .andExpect(jsonPath("$.sourceBlock.reason").value("PROFILE_REQUIRED"))
        .andExpect(jsonPath("$.sourceBlock.responsible").value("Verwaltende der Bibliothek"))
        .andExpect(jsonPath("$.sourceBlock.notice").value(Matchers.startsWith("Gesperrt")));
    mockMvc
        .perform(as("dev-admin", get("/api/v1/libraries")))
        .andExpect(
            jsonPath("$[?(@.id == '" + repaired + "')].sourceBlock.reason")
                .value(Matchers.hasItem("PROFILE_REQUIRED")));
    mockMvc
        .perform(as("dev-admin", get("/api/v1/catalog")))
        .andExpect(
            jsonPath(
                    "$.entries[?(@.assetId == '"
                        + repaired
                        + "')].knowledgeLibrary.sourceBlock.reason")
                .value(Matchers.hasItem("PROFILE_REQUIRED")));
    assertThat(freezeOf(document))
        .extracting(ChatSource::getFreezeReason, ChatSource::getFreezeResponsible)
        .containsExactly("PROFILE_REQUIRED", "Verwaltende der Bibliothek");

    mockMvc
        .perform(
            as("dev-admin", put("/api/v1/libraries/" + repaired + "/connection-profile"))
                .content("{\"profileId\": \"" + profile + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sourceBlock").doesNotExist());
    run(repaired, "COMPLETED");
    assertThat(freezeOf(document)).isNull();

    mockMvc
        .perform(as("dev-admin", put(REQUIREMENT)).content("{\"required\": false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.profileRequired").value(false))
        .andExpect(jsonPath("$.ownAddressStock").doesNotExist());
    run(waiting, "COMPLETED");

    assertThat(
            jdbc.queryForList(
                "SELECT event_type FROM audit_log WHERE object_id = ?",
                String.class,
                UUID.nameUUIDFromBytes(
                        ("io.opaa.connection.type:" + TYPE).getBytes(StandardCharsets.UTF_8))
                    .toString()))
        .contains("CONNECTOR_PROFILE_REQUIRED", "CONNECTOR_PROFILE_OPTIONAL");
  }

  /**
   * Acceptance criterion: deleting the last profile that could take over stays possible; its impact
   * warns about it beforehand.
   */
  @Test
  void theImpactOfTheLastProfileWarnsAndDeletingItStaysPossible() throws Exception {
    UUID first = createProfile("Zugang erster", "LIBRARY");
    mockMvc
        .perform(as("dev-admin", get(PROFILES + "/" + first + "/impact")))
        .andExpect(jsonPath("$.lastForProfileRequirement").value(false));
    mockMvc.perform(require("RUNS")).andExpect(status().isOk());
    mockMvc
        .perform(as("dev-admin", get(PROFILES + "/" + first + "/impact")))
        .andExpect(jsonPath("$.lastForProfileRequirement").value(true));

    UUID second = createProfile("Zugang zweiter", "LIBRARY");
    mockMvc
        .perform(as("dev-admin", get(PROFILES + "/" + first + "/impact")))
        .andExpect(jsonPath("$.lastForProfileRequirement").value(false));
    lockProfile(second);
    mockMvc
        .perform(as("dev-admin", delete(PROFILES + "/" + first)))
        .andExpect(status().isNoContent());
    profiles.remove(first);
    mockMvc
        .perform(as("dev-admin", get("/api/v1/admin/connector-types")))
        .andExpect(jsonPath("$[?(@.sourceType == '" + TYPE + "')].profileRequired").value(true));
  }

  /**
   * Regression guard: a library whose profile was deleted ("Zugang entfernt") keeps its connection
   * row without a profile and counts as one with its own address - its address stays frozen, a test
   * with a new address is refused, it is listed, and a locked stock locks it.
   */
  @Test
  void aLibraryWhoseProfileWasDeletedCountsAsOneWithItsOwnAddress() throws Exception {
    UUID doomed = createProfile("Zugang weg", "LIBRARY");
    createProfile("Zugang bleibt", "LIBRARY");
    UUID library = createLibrary(doomed);
    mockMvc.perform(require("RUNS")).andExpect(status().isOk());
    mockMvc
        .perform(as("dev-admin", delete(PROFILES + "/" + doomed)))
        .andExpect(status().isNoContent());
    profiles.remove(doomed);

    expectProfileRequired(update(library, SERVER + "/anders", null));
    expectProfileRequired(sourceTest(library, SERVER + "/anders"));
    mockMvc
        .perform(as("dev-admin", get(REQUIREMENT)))
        .andExpect(
            jsonPath("$.ownAddressLibraries[*].id").value(Matchers.hasItem(library.toString())));

    mockMvc.perform(require("LOCKED")).andExpect(status().isOk());
    mockMvc
        .perform(as("dev-admin", get("/api/v1/libraries/" + library)))
        .andExpect(jsonPath("$.sourceBlock.reason").value("PROFILE_REQUIRED"));
  }

  /**
   * A second switch-on with another stock choice is its own governance event, keeps the start of
   * the requirement and locks the stock at once; a test through the library with a new address is
   * refused while it runs on.
   */
  @Test
  void anotherStockChoiceIsItsOwnEventAndKeepsTheStart() throws Exception {
    createProfile("Zugang Wahl", "LIBRARY");
    UUID own = createOwnLibrary(SERVER + "/wahl");
    long before = requiredEvents();
    String first =
        mockMvc
            .perform(require("RUNS"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    String requiredAt = JsonPath.read(first, "$.profileRequiredAt");
    expectProfileRequired(sourceTest(own, SERVER + "/neu"));
    mockMvc
        .perform(sourceTest(own, SERVER + "/wahl"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reachable").value(true));

    mockMvc
        .perform(require("LOCKED"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ownAddressStock").value("LOCKED"))
        .andExpect(jsonPath("$.profileRequiredAt").value(requiredAt));
    mockMvc.perform(require("LOCKED")).andExpect(status().isOk());

    assertThat(requiredEvents() - before).isEqualTo(2);
    mockMvc
        .perform(as("dev-admin", get("/api/v1/libraries/" + own)))
        .andExpect(jsonPath("$.sourceBlock.reason").value("PROFILE_REQUIRED"));
  }

  private long requiredEvents() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM audit_log WHERE object_id = ? AND event_type = ?",
        Long.class,
        UUID.nameUUIDFromBytes(("io.opaa.connection.type:" + TYPE).getBytes(StandardCharsets.UTF_8))
            .toString(),
        "CONNECTOR_PROFILE_REQUIRED");
  }

  private MockHttpServletRequestBuilder sourceTest(UUID library, String url) {
    return as("dev-admin", post("/api/v1/libraries/source-test"))
        .content(
            "{\"sourceType\": \""
                + TYPE
                + "\", \"libraryId\": \""
                + library
                + "\", \"sourceUrl\": \""
                + url
                + "\"}");
  }

  private void expectProfileRequired(MockHttpServletRequestBuilder request) throws Exception {
    mockMvc
        .perform(request)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("PROFILE_REQUIRED"))
        .andExpect(jsonPath("$.error").value(Matchers.containsString("Testquelle mit Zugang")));
  }

  private MockHttpServletRequestBuilder require(String stock) {
    return as("dev-admin", put(REQUIREMENT))
        .content("{\"required\": true, \"ownAddressStock\": \"" + stock + "\"}");
  }

  private MockHttpServletRequestBuilder update(UUID library, String url, String credentials) {
    return as("dev-admin", put("/api/v1/libraries/" + library))
        .content(
            "{\"name\": \"Geändert "
                + library
                + "\", \"sourceUrl\": \""
                + url
                + "\""
                + (credentials == null ? "" : ", \"sourceCredentials\": \"" + credentials + "\"")
                + "}");
  }

  private UUID createProfile(String name, String ownership) throws Exception {
    String body =
        mockMvc
            .perform(
                as("dev-admin", post(PROFILES))
                    .content(
                        """
                        {"name": "%s %s", "sourceType": "%s", "serverUrl": "%s",
                         "authMethod": "NONE", "ownership": "%s"}
                        """
                            .formatted(name, UUID.randomUUID(), TYPE, SERVER, ownership)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
    profiles.add(id);
    return id;
  }

  private void lockProfile(UUID profile) throws Exception {
    mockMvc
        .perform(
            as("dev-admin", put(PROFILES + "/" + profile + "/lock")).content("{\"locked\": true}"))
        .andExpect(status().isOk());
  }

  private UUID createOwnLibrary(String url) throws Exception {
    return create(
        "{\"name\": \"Eigen "
            + UUID.randomUUID()
            + "\", \"sourceType\": \""
            + TYPE
            + "\", \"sourceUrl\": \""
            + url
            + "\"}");
  }

  private UUID createLibrary(UUID profile) throws Exception {
    return create(
        "{\"name\": \"Zugang "
            + UUID.randomUUID()
            + "\", \"sourceType\": \""
            + TYPE
            + "\", \"connectionProfileId\": \""
            + profile
            + "\"}");
  }

  private UUID create(String json) throws Exception {
    String body =
        mockMvc
            .perform(as("dev-admin", post("/api/v1/libraries")).content(json))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
    libraries.add(id);
    return id;
  }

  private UUID document(UUID library) {
    io.opaa.knowledge.Document document =
        new io.opaa.knowledge.Document(
            "beleg.md", "/beleg.md", "text/markdown", 1L, SourceType.of(TYPE));
    document.setLibraryId(library);
    document.setOrganizationId(Organization.DEFAULT_ID);
    document.setIndexedAt(Instant.parse("2025-01-10T08:00:00Z"));
    return documentRepository.save(document).getId();
  }

  /** The source row an answer citing {@code documentId} carries, {@code null} while updated. */
  private ChatSource freezeOf(UUID documentId) {
    ChatSource source =
        sourceAssembler
            .assemble(
                List.of(
                    org.springframework.ai.document.Document.builder()
                        .text("Beleg")
                        .metadata(
                            Map.of("file_name", "beleg.md", "document_id", documentId.toString()))
                        .score(0.9)
                        .build()),
                List.of(),
                MetadataFilter.NONE)
            .getFirst();
    return source.getFreezeReason() == null ? null : source;
  }

  private void run(UUID library, String expectedStatus) throws Exception {
    mockMvc
        .perform(as("dev-admin", post("/api/v1/libraries/" + library + "/indexing")))
        .andExpect(status().isAccepted());
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                mockMvc
                    .perform(
                        as("dev-admin", get("/api/v1/libraries/" + library + "/indexing/status")))
                    .andExpect(jsonPath("$.status").value(expectedStatus)));
  }

  private String lastRunMessage(UUID library) throws Exception {
    String body =
        mockMvc
            .perform(as("dev-admin", get("/api/v1/libraries/" + library + "/indexing/status")))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    return JsonPath.read(body, "$.message");
  }

  private static MockHttpServletRequestBuilder as(
      String user, MockHttpServletRequestBuilder request) {
    return request
        .header(DevAuthFilter.DEV_USER_HEADER, user)
        .contentType(MediaType.APPLICATION_JSON);
  }
}
