package io.opaa.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.DocumentStatus;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.DevAuthFilter;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.externalaccess.ExternalAccessMassRetrievalAlarm;
import io.opaa.externalaccess.ExternalAccessSettings;
import io.opaa.externalaccess.ExternalAccessSettingsService;
import io.opaa.externalaccess.token.ExternalAccessTokenService;
import io.opaa.indexing.chunk.ChunkingService;
import io.opaa.indexing.chunk.VectorChunkStore;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.library.LibraryExternalAccessService;
import io.opaa.test.OpaaIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.context.support.StandardServletEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The MCP server over real HTTP (#1721, ADR-0035): the handshake, the tool catalogue built per
 * token, a call of each of the three tools, the two answers a closed channel owes, and the refusal
 * of a protocol revision this installation does not serve.
 *
 * <p>Real HTTP rather than MockMvc, because the endpoint is a {@code RouterFunction} of the Spring
 * AI transport with its own header contract ({@code Accept} must name both media types) - a test
 * that bypassed the servlet stack would prove nothing about what a client experiences.
 *
 * <p>Two tokens of the same person with different selections carry the decisive assertion: the tool
 * descriptions differ, and a library outside a token's selection yields no hit through it even
 * though the person may read it.
 */
@OpaaIntegrationTest
class McpServerIntegrationTest {

  private static final UUID DEFAULT_ORGANIZATION_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

  private static final String QUESTION = "Welche Frist gilt für den Widerspruch?";
  private static final String SERVED_LIBRARY = "MCP-Fristenbibliothek";
  private static final String UNSELECTED_LIBRARY = "MCP-Kantinenbibliothek";
  private static final String MODERN_VERSION = "2026-07-28";

  /** The delivered {@code opaa.mcp.excerpt-characters}; this class runs the shipped signature. */
  private static final int EXCERPT_CHARACTERS = 500;

  private static final String FILLER_SENTENCE = "Allgemeine Vorbemerkung zum Verfahren. ";

  @LocalServerPort private int port;

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private JsonMapper jsonMapper;
  @Autowired private VectorStore vectorStore;
  @Autowired private VectorChunkStore vectorChunkStore;
  @Autowired private DocumentRepository documents;
  @Autowired private UserRepository users;
  @Autowired private ExternalAccessSettingsService settings;
  @Autowired private ExternalAccessTokenService tokenService;
  @Autowired private LibraryExternalAccessService libraryRelease;
  @Autowired private ExternalAccessMassRetrievalAlarm alarm;
  @Autowired private Environment environment;
  @Autowired private Clock clock;

  private final HttpClient http = HttpClient.newHttpClient();

  private UUID ownerId;
  private UUID administratorId;
  private UUID servedLibraryId;
  private UUID unselectedLibraryId;
  private UUID servedDocumentId;
  private UUID longDocumentId;
  private UUID unselectedDocumentId;
  private String narrowToken;
  private String wideToken;

  @BeforeEach
  void setUp() throws Exception {
    mockMvc.perform(get("/api/v1/auth/me").with(devUser("dev-user"))).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/auth/me").with(devUser("dev-admin"))).andExpect(status().isOk());
    ownerId = userIdOf("dev-user");
    administratorId = userIdOf("dev-admin");
    removeOwnRows();
    // The alert window is one shared singleton; this class both drives and asserts on it.
    alarm.reset();

    setChannelEnabled(true);
    servedLibraryId = insertLibrary(SERVED_LIBRARY, ownerId);
    unselectedLibraryId = insertLibrary(UNSELECTED_LIBRARY, ownerId);
    servedDocumentId =
        insertDocument(
            servedLibraryId,
            "widerspruch.md",
            List.of(
                passage(
                    0,
                    "Die Widerspruchsfrist beträgt einen Monat."
                        + " Sie beginnt mit der Bekanntgabe des Bescheids.",
                    "Abschn. Verfahren › Fristsetzung"),
                passage(
                    1,
                    "Die Behörde entscheidet über den Widerspruch innerhalb von drei Monaten.",
                    "Abschn. Verfahren › Entscheidung")));
    // One passage far above the excerpt length, with the found place in its middle: only such a
    // passage can show that the excerpt is a window and not the passage itself.
    longDocumentId =
        insertDocument(
            servedLibraryId,
            "langtext.md",
            List.of(
                passage(
                    0,
                    FILLER_SENTENCE.repeat(30)
                        + "Die Widerspruchsfrist im Langtext beträgt vier Wochen. "
                        + FILLER_SENTENCE.repeat(30),
                    "Abschn. Langtext")));
    unselectedDocumentId =
        insertDocument(
            unselectedLibraryId,
            "kantine.md",
            List.of(passage(0, "Die Widerspruchsfrist der Kantinenordnung ist eine Woche.", null)));

    Instant releaseUntil = clock.instant().plus(Duration.ofDays(60));
    libraryRelease.setExternalAccess(owner(), servedLibraryId, true, releaseUntil);
    libraryRelease.setExternalAccess(owner(), unselectedLibraryId, true, releaseUntil);

    narrowToken = issue("Eng", List.of(servedLibraryId));
    wideToken = issue("Weit", List.of(servedLibraryId, unselectedLibraryId));
  }

  @AfterEach
  void tearDown() {
    alarm.reset();
    removeOwnRows();
    vectorChunkStore.deleteByLibraryId(servedLibraryId);
    vectorChunkStore.deleteByLibraryId(unselectedLibraryId);
    jdbc.update(
        "DELETE FROM documents WHERE library_id IN (?, ?)", servedLibraryId, unselectedLibraryId);
    jdbc.update(
        "DELETE FROM asset_grants WHERE asset_id IN (?, ?)", servedLibraryId, unselectedLibraryId);
    jdbc.update(
        "DELETE FROM asset_visibility_history WHERE asset_id IN (?, ?)",
        servedLibraryId,
        unselectedLibraryId);
    jdbc.update(
        "DELETE FROM audit_log WHERE object_id IN (?, ?)",
        servedLibraryId.toString(),
        unselectedLibraryId.toString());
    jdbc.update("DELETE FROM assets WHERE id IN (?, ?)", servedLibraryId, unselectedLibraryId);
  }

  @Test
  void theHandshakeAnswersWithTheInstructionsOfTheInstallationAndTheServedRevisions() {
    JsonNode response = rpc(narrowToken, initialize("2025-06-18"));

    JsonNode result = response.get("result");
    assertThat(result.get("protocolVersion").asString()).isEqualTo("2025-06-18");
    assertThat(result.get("instructions").asString())
        .isEqualTo(settings.current().values().serverInstructions())
        .isNotBlank();
    assertThat(result.get("capabilities").has("tools")).isTrue();
    // Resources, prompts and completions are switched off: each is an exposure surface of its own.
    assertThat(result.get("capabilities").has("resources")).isFalse();
    assertThat(result.get("capabilities").has("prompts")).isFalse();
    assertThat(versions(result.get("_meta").get(OpaaMcpHandler.SUPPORTED_VERSIONS_KEY)))
        .contains("2025-06-18");
  }

  @Test
  void aRevisionThisInstallationDoesNotServeIsRefusedWithTheServedOnesNamed() {
    JsonNode response = rpc(narrowToken, initialize(MODERN_VERSION));

    assertThat(response.has("result")).isFalse();
    JsonNode error = response.get("error");
    // Deliberately not -32022: that code is the marker of a server speaking the per-request
    // negotiation, and it would take a dual-era client's fallback to the handshake away.
    assertThat(error.get("code").asInt()).isEqualTo(-32602).isNotEqualTo(-32022);
    assertThat(error.get("message").asString()).contains(MODERN_VERSION).contains("2025-11-25");
    assertThat(versions(error.get("data").get(OpaaMcpHandler.SUPPORTED_VERSIONS_KEY))).isNotEmpty();
  }

  @Test
  void aRequestNamingAModernRevisionInItsHeaderIsRefusedWithoutAModernErrorBody() {
    HttpResponse<String> response =
        send(
            request(narrowToken, call("tools/list", Map.of()))
                .header(McpRequestContext.PROTOCOL_VERSION_HEADER, MODERN_VERSION)
                .build());

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains(MODERN_VERSION).contains("2025-11-25");
    // The body must carry no modern error object - a dual-era client that finds none falls back
    // to the initialize handshake, which is the way that works here.
    assertThat(response.body()).doesNotContain("-32022").doesNotContain("jsonrpc");
  }

  @Test
  void theToolCatalogueNamesExactlyTheThreeToolsAndTheHoldingsOfThisToken() {
    JsonNode narrow = rpc(narrowToken, call("tools/list", Map.of())).get("result").get("tools");

    assertThat(names(narrow)).containsExactlyInAnyOrder("search", "fetch", "list_libraries");
    for (JsonNode tool : narrow) {
      assertThat(tool.get("description").asString())
          .as("every description names the holdings of this token and no other")
          .contains(SERVED_LIBRARY)
          .doesNotContain(UNSELECTED_LIBRARY);
    }

    JsonNode wide = rpc(wideToken, call("tools/list", Map.of())).get("result").get("tools");
    assertThat(wide.get(0).get("description").asString())
        .contains(SERVED_LIBRARY)
        .contains(UNSELECTED_LIBRARY);
  }

  @Test
  void listLibrariesReturnsTheEffectiveViewOfTheToken() {
    JsonNode result = structured(rpc(narrowToken, toolCall("list_libraries", Map.of())));

    List<String> names = new ArrayList<>();
    result.get("libraries").forEach(library -> names.add(library.get("name").asString()));
    assertThat(names).contains(SERVED_LIBRARY).doesNotContain(UNSELECTED_LIBRARY);
  }

  @Test
  void searchReturnsHitsOfTheTokensHoldingsAndNothingFromOutsideThem() {
    JsonNode result =
        structured(rpc(narrowToken, toolCall("search", Map.of("query", QUESTION, "maxHits", 5))));

    JsonNode hits = result.get("results");
    assertThat(hits.size()).isPositive();
    for (JsonNode hit : hits) {
      assertThat(hit.get("libraryId").asString()).isEqualTo(servedLibraryId.toString());
      assertThat(hit.get("id").asString()).isNotBlank();
      assertThat(hit.get("text").asString()).isNotBlank();
    }
    assertThat(hits.get(0).get("score").asDouble()).isEqualTo(1.0);

    // A library the person may read and that is released, but that this token did not select: the
    // permission filter sits in the search, so naming it yields nothing rather than an error.
    JsonNode narrowed =
        structured(
            rpc(
                narrowToken,
                toolCall(
                    "search",
                    Map.of(
                        "query",
                        QUESTION,
                        "libraryIds",
                        List.of(unselectedLibraryId.toString())))));
    assertThat(narrowed.get("results")).isEmpty();
  }

  /**
   * A hit names {@code id} and {@code documentId}; reaching for either must work (#1766). The
   * document path without {@code whole} enters the document at its first passage.
   */
  @Test
  void fetchAcceptsTheDocumentIdOfAHitJustAsWellAsItsHitId() {
    JsonNode passage =
        structured(
            rpc(narrowToken, toolCall("fetch", Map.of("documentId", servedDocumentId.toString()))));

    assertThat(passage.get("whole").asBoolean()).isFalse();
    assertThat(passage.get("documentId").asString()).isEqualTo(servedDocumentId.toString());
    assertThat(passage.get("text").asString()).contains("Widerspruchsfrist beträgt einen Monat");

    JsonNode whole =
        structured(
            rpc(
                narrowToken,
                toolCall(
                    "fetch", Map.of("documentId", servedDocumentId.toString(), "whole", true))));
    assertThat(whole.get("whole").asBoolean()).isTrue();
    assertThat(whole.get("text").asString()).contains("innerhalb von drei Monaten");
  }

  /**
   * The document of a library outside this token's selection answers exactly like an unknown one,
   * and a call naming neither field says in German which two it offers.
   */
  @Test
  void fetchByDocumentIdStaysInsideTheScopeAndNamesBothFieldsWhenNeitherWasGiven() {
    JsonNode foreign =
        rpc(narrowToken, toolCall("fetch", Map.of("documentId", unselectedDocumentId.toString())))
            .get("result");
    JsonNode unknown =
        rpc(narrowToken, toolCall("fetch", Map.of("documentId", UUID.randomUUID().toString())))
            .get("result");
    assertThat(foreign.get("isError").asBoolean()).isTrue();
    assertThat(foreign.get("content").get(0).get("text").asString())
        .isEqualTo(unknown.get("content").get(0).get("text").asString());

    JsonNode neither = rpc(narrowToken, toolCall("fetch", Map.of())).get("result");
    assertThat(neither.get("isError").asBoolean()).isTrue();
    assertThat(neither.get("content").get(0).get("text").asString())
        .contains("id")
        .contains("documentId");
  }

  /**
   * What a foreign model pays for out of its context window (#1766): one entry per document, each
   * with a bounded excerpt instead of the whole passage, and the further passages of that document
   * only as fetchable ids.
   */
  @Test
  void searchSummarisesPerDocumentAndAnswersWithBoundedExcerpts() {
    JsonNode results =
        structured(rpc(narrowToken, toolCall("search", Map.of("query", QUESTION)))).get("results");

    List<String> documentIds = new ArrayList<>();
    results.forEach(hit -> documentIds.add(hit.get("documentId").asString()));
    assertThat(documentIds).doesNotHaveDuplicates();
    assertThat(documentIds).contains(longDocumentId.toString());
    for (JsonNode hit : results) {
      assertThat(hit.get("text").asString().length())
          .as("the excerpt is bounded, the passage behind it is one fetch away")
          .isLessThanOrEqualTo(EXCERPT_CHARACTERS + 2);
      assertThat(hit.has("moreHits")).isTrue();
    }

    JsonNode longHit = hitOf(results, longDocumentId);
    // The window sits around the found place, not at the beginning of a long passage.
    assertThat(longHit.get("text").asString()).contains("Widerspruchsfrist im Langtext");
    // The found place sits in the middle of the passage, so the window begins cut off.
    assertThat(longHit.get("text").asString()).startsWith("…").endsWith("…");
    // The passage itself is still whole behind the hit id.
    assertThat(
            structured(
                    rpc(narrowToken, toolCall("fetch", Map.of("id", longHit.get("id").asString()))))
                .get("text")
                .asString()
                .length())
        .isGreaterThan(EXCERPT_CHARACTERS);
  }

  /** The second passage of the same document is a location of the one entry, not an entry. */
  @Test
  void furtherPassagesOfADocumentAreListedAsLocationsOfItsEntry() {
    JsonNode results =
        structured(rpc(narrowToken, toolCall("search", Map.of("query", QUESTION)))).get("results");

    JsonNode entry = hitOf(results, servedDocumentId);
    List<String> ids = new ArrayList<>();
    ids.add(entry.get("id").asString());
    entry.get("moreHits").forEach(further -> ids.add(further.get("id").asString()));
    assertThat(ids).doesNotHaveDuplicates();
    // Every id of the entry is a fetchable hit of this document.
    for (String id : ids) {
      assertThat(
              structured(rpc(narrowToken, toolCall("fetch", Map.of("id", id))))
                  .get("documentId")
                  .asString())
          .isEqualTo(servedDocumentId.toString());
    }
  }

  /**
   * Every client sends it right after the handshake. It is accepted, and it produces no WARN - a
   * line per connecting client would be noise in the log of the installation (#1766).
   */
  @Test
  void theInitializedNotificationIsAcceptedWithoutAWarning() {
    Logger handlerLogger =
        (Logger)
            LoggerFactory.getLogger(
                "io.modelcontextprotocol.server.DefaultMcpStatelessServerHandler");
    ListAppender<ILoggingEvent> recorded = new ListAppender<>();
    recorded.start();
    handlerLogger.addAppender(recorded);
    try {
      Map<String, Object> notification = new LinkedHashMap<>();
      notification.put("jsonrpc", "2.0");
      notification.put("method", "notifications/initialized");
      notification.put("params", Map.of());

      HttpResponse<String> response = send(request(narrowToken, notification).build());

      assertThat(response.statusCode()).isBetween(200, 204);
      assertThat(response.body()).doesNotContain("error");
      assertThat(recorded.list)
          .as("the library's 'Missing handler for notification type' line")
          .isEmpty();
    } finally {
      handlerLogger.detachAppender(recorded);
    }
  }

  private static JsonNode hitOf(JsonNode results, UUID documentId) {
    for (JsonNode hit : results) {
      if (documentId.toString().equals(hit.get("documentId").asString())) {
        return hit;
      }
    }
    throw new IllegalStateException("no hit for document " + documentId);
  }

  @Test
  void fetchReturnsThePassageByDefaultAndTheWholeDocumentOnlyOnRequest() {
    String hitId = firstHitId();

    JsonNode passage = structured(rpc(narrowToken, toolCall("fetch", Map.of("id", hitId))));
    assertThat(passage.get("whole").asBoolean()).isFalse();
    assertThat(passage.get("documentId").asString()).isEqualTo(servedDocumentId.toString());
    assertThat(passage.get("text").asString()).contains("Widerspruchsfrist beträgt einen Monat");

    JsonNode whole =
        structured(rpc(narrowToken, toolCall("fetch", Map.of("id", hitId, "whole", true))));
    assertThat(whole.get("whole").asBoolean()).isTrue();
    assertThat(whole.get("text").asString()).contains("innerhalb von drei Monaten");

    JsonNode unknown =
        rpc(narrowToken, toolCall("fetch", Map.of("id", UUID.randomUUID().toString())))
            .get("result");
    assertThat(unknown.get("isError").asBoolean()).isTrue();
  }

  @Test
  void aCallWithoutAUsableTokenNeverReachesTheEndpoint() {
    assertThat(send(request(null, call("tools/list", Map.of())).build()).statusCode())
        .as("no bearer value at all, with an open channel")
        .isEqualTo(401);
    assertThat(
            send(request("opaa_pat_erfunden", call("tools/list", Map.of())).build()).statusCode())
        .isEqualTo(401);

    // Under local,dev every other path would be authenticated as the development user; the
    // channel's chain claims this one before that filter ever runs.
    HttpResponse<String> asDevelopmentUser =
        send(
            request(null, call("tools/list", Map.of()))
                .header(DevAuthFilter.DEV_USER_HEADER, "dev-admin")
                .build());
    assertThat(asDevelopmentUser.statusCode()).isEqualTo(401);
  }

  @Test
  void aClosedChannelAnswers503WithAUsableTokenAnd404WithoutOne() {
    setChannelEnabled(false);

    HttpResponse<String> withToken =
        send(request(narrowToken, call("tools/list", Map.of())).build());
    assertThat(withToken.statusCode()).isEqualTo(503);
    assertThat(withToken.body()).contains("channel_closed").contains("ausgeschaltet");

    assertThat(send(request(null, call("tools/list", Map.of())).build()).statusCode())
        .as("without a usable token a closed channel does not confirm that the service exists")
        .isEqualTo(404);
    assertThat(
            send(request("opaa_pat_erfunden", call("tools/list", Map.of())).build()).statusCode())
        .isEqualTo(404);

    // Per call, not per session: the very next request of the same client is decided anew.
    setChannelEnabled(true);
    assertThat(send(request(narrowToken, call("tools/list", Map.of())).build()).statusCode())
        .isEqualTo(200);
  }

  /**
   * The path is decided by the same parser everywhere. {@code /%6Dcp} reaches the endpoint - the
   * chain and the library's route match the decoded path - while a raw comparison of {@code
   * getRequestURI()} would call it a different path: the endpoint would answer while the version
   * check and this channel's refusal form stood down.
   */
  @Test
  void anEncodedFormOfThePathIsTheSameEndpoint() {
    HttpResponse<String> modernOnAVariant =
        send(
            request(narrowToken, call("tools/list", Map.of()), "/%6Dcp")
                .header(McpRequestContext.PROTOCOL_VERSION_HEADER, MODERN_VERSION)
                .build());
    assertThat(modernOnAVariant.statusCode())
        .as("the version check must not be skipped by a percent escape")
        .isEqualTo(400);

    setChannelEnabled(false);
    assertThat(send(request(null, call("tools/list", Map.of()), "/%6Dcp").build()).statusCode())
        .as("a closed channel owes the same 404 on every form of its path")
        .isEqualTo(404);
  }

  /** A matrix variable never reaches any of this: the strict firewall refuses the request. */
  @Test
  void aPathWithAMatrixVariableIsRefusedBeforeTheEndpoint() {
    assertThat(
            send(request(narrowToken, call("tools/list", Map.of()), "/mcp;x=1").build())
                .statusCode())
        .isEqualTo(403);
  }

  /**
   * The effective view hangs on the tool call running on the request's thread: the token id comes
   * from {@code RequestContextHolder}, and without it the scope would widen to everything the
   * person may read. Two things hold that here - the servlet environment, which is what makes the
   * stateless autoconfiguration execute tools immediately, and the quota, which is keyed by the
   * token and could not apply at all if the id were invisible.
   */
  @Test
  void theToolsRunOnTheThreadOfTheRequestSoTheTokenDecides() {
    assertThat(environment).isInstanceOf(StandardServletEnvironment.class);
    setChannelEnabled(true, 1);

    rpc(narrowToken, toolCall("search", Map.of("query", QUESTION)));
    JsonNode refused =
        rpc(narrowToken, toolCall("search", Map.of("query", QUESTION))).get("result");

    assertThat(refused.get("isError").asBoolean()).isTrue();
    assertThat(refused.get("content").get(0).get("text").asString()).contains("Kontingent");
  }

  /** {@code tools/list} is no free path either - it counts, and says so when the quota is out. */
  @Test
  void theToolListingCountsAgainstTheQuotaOfTheToken() {
    setChannelEnabled(true, 1);

    rpc(narrowToken, call("tools/list", Map.of()));
    JsonNode refused = rpc(narrowToken, call("tools/list", Map.of()));

    assertThat(refused.has("result")).isFalse();
    assertThat(refused.get("error").get("message").asString()).contains("Kontingent");
  }

  /** The channel alert counts MCP retrievals too, and names the token rather than a person. */
  @Test
  void aRetrievalOverMcpCountsTowardsTheChannelAlert() {
    setChannelEnabled(true, 60, 1);

    rpc(narrowToken, toolCall("search", Map.of("query", QUESTION)));
    rpc(narrowToken, toolCall("search", Map.of("query", QUESTION)));

    assertThat(alertsForTheAdministrator()).isPositive();
  }

  private long alertsForTheAdministrator() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM notifications WHERE recipient_user_id = ?"
            + " AND type = 'EXTERNAL_ACCESS_MASS_RETRIEVAL'",
        Long.class,
        administratorId);
  }

  /** The promise of security-and-compliance.md: the single query leaves no trail. */
  @Test
  void noToolCallWritesAnAuditEntry() {
    long before = auditEntriesOfThisPerson();

    rpc(narrowToken, toolCall("list_libraries", Map.of()));
    rpc(narrowToken, toolCall("search", Map.of("query", QUESTION)));
    rpc(narrowToken, toolCall("fetch", Map.of("id", firstHitId())));

    assertThat(auditEntriesOfThisPerson()).isEqualTo(before);
  }

  /** Only rows about this class's own actor - a count over the table would be open to others. */
  private long auditEntriesOfThisPerson() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM audit_log WHERE actor_ref = ?", Long.class, ownerId.toString());
  }

  private String firstHitId() {
    JsonNode result =
        structured(rpc(narrowToken, toolCall("search", Map.of("query", QUESTION, "maxHits", 5))));
    for (JsonNode hit : result.get("results")) {
      if (servedDocumentId.toString().equals(hit.get("documentId").asString())) {
        return hit.get("id").asString();
      }
    }
    throw new IllegalStateException("the fixture document produced no hit");
  }

  private static List<String> names(JsonNode tools) {
    List<String> names = new ArrayList<>();
    tools.forEach(tool -> names.add(tool.get("name").asString()));
    return names;
  }

  private static List<String> versions(JsonNode node) {
    List<String> versions = new ArrayList<>();
    node.forEach(version -> versions.add(version.asString()));
    return versions;
  }

  private JsonNode structured(JsonNode response) {
    JsonNode result = response.get("result");
    assertThat(result.path("isError").asBoolean(false)).as("the tool call succeeded").isFalse();
    return result.get("structuredContent");
  }

  private Map<String, Object> initialize(String protocolVersion) {
    return call(
        "initialize",
        Map.of(
            "protocolVersion",
            protocolVersion,
            "capabilities",
            Map.of(),
            "clientInfo",
            Map.of("name", "opaa-test", "version", "1")));
  }

  private Map<String, Object> toolCall(String tool, Map<String, Object> arguments) {
    return call("tools/call", Map.of("name", tool, "arguments", arguments));
  }

  private Map<String, Object> call(String method, Map<String, Object> params) {
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("jsonrpc", "2.0");
    request.put("id", 1);
    request.put("method", method);
    request.put("params", params);
    return request;
  }

  private JsonNode rpc(String token, Map<String, Object> body) {
    HttpResponse<String> response = send(request(token, body).build());
    assertThat(response.statusCode()).isEqualTo(200);
    return jsonMapper.readTree(response.body());
  }

  private HttpRequest.Builder request(String token, Map<String, Object> body) {
    return request(token, body, "/mcp");
  }

  private HttpRequest.Builder request(String token, Map<String, Object> body, String path) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            // Both media types: the Streamable HTTP transport refuses anything else with 400.
            .header("Accept", "application/json, text/event-stream")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    jsonMapper.writeValueAsString(body), StandardCharsets.UTF_8));
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    return builder;
  }

  private HttpResponse<String> send(HttpRequest request) {
    try {
      return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private String issue(String name, List<UUID> libraryIds) {
    return tokenService
        .issue(
            ownerId,
            DEFAULT_ORGANIZATION_ID,
            name,
            libraryIds,
            clock.instant().plus(Duration.ofDays(30)))
        .rawValue();
  }

  /**
   * The installation-wide settings row; {@code SeededRowRestorer} puts it back after the method.
   */
  private void setChannelEnabled(boolean enabled) {
    ExternalAccessSettings.Values values = settings.current().values();
    setChannelEnabled(
        enabled, values.tokenRateLimitPerHour(), values.massRetrievalAlertThreshold());
  }

  private void setChannelEnabled(boolean enabled, int quotaPerHour) {
    setChannelEnabled(
        enabled, quotaPerHour, settings.current().values().massRetrievalAlertThreshold());
  }

  private void setChannelEnabled(boolean enabled, int quotaPerHour, int alertThreshold) {
    ExternalAccessSettings.Values values = settings.current().values();
    settings.update(
        CurrentUser.of(administratorId, DEFAULT_ORGANIZATION_ID, SystemRole.SYSTEM_ADMIN, "Admin"),
        new ExternalAccessSettingsService.Update(
            enabled,
            values.tokenMaxLifetimeDays(),
            quotaPerHour,
            values.allowedCidrs(),
            alertThreshold,
            values.serverInstructions()));
  }

  private void removeOwnRows() {
    jdbc.update("DELETE FROM external_access_tokens WHERE user_id = ?", ownerId);
    jdbc.update("DELETE FROM audit_log WHERE actor_ref = ?", ownerId.toString());
    // The switch is flipped as dev-admin, an actor many classes share - so only this class's own
    // event type is removed, never everything that actor ever wrote.
    jdbc.update(
        "DELETE FROM audit_log WHERE actor_ref = ? AND event_type = 'EXTERNAL_ACCESS_SETTINGS_CHANGED'",
        administratorId.toString());
    jdbc.update(
        "DELETE FROM notifications WHERE recipient_user_id = ?"
            + " AND type = 'EXTERNAL_ACCESS_MASS_RETRIEVAL'",
        administratorId);
  }

  private CurrentUser owner() {
    return CurrentUser.of(ownerId, DEFAULT_ORGANIZATION_ID, SystemRole.USER, "Verantwortliche");
  }

  private UUID userIdOf(String subject) {
    return users.findBySubjectAndIssuer(subject, "opaa-dev").map(User::getId).orElseThrow();
  }

  private UUID insertLibrary(String name, UUID owner) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "WITH shell AS (INSERT INTO assets (id, asset_type, organization_id, name, owner_type,"
            + " owner_user_id, listed) VALUES (?, 'KNOWLEDGE_LIBRARY', ?, ?, 'USER', ?, false)"
            + " RETURNING id, organization_id) INSERT INTO knowledge_libraries (id,"
            + " organization_id, source_type) SELECT id, organization_id, 'UPLOAD' FROM shell",
        id,
        DEFAULT_ORGANIZATION_ID,
        name,
        owner);
    jdbc.update(
        "INSERT INTO asset_grants (id, asset_type, asset_id, organization_id, subject_type, subject_user_id,"
            + " role, created_at, updated_at) VALUES (?, 'KNOWLEDGE_LIBRARY', ?, ?, 'USER', ?, 'OWNER', now(), now())",
        UUID.randomUUID(),
        id,
        DEFAULT_ORGANIZATION_ID,
        owner);
    return id;
  }

  private record Passage(int index, String text, String location) {}

  private static Passage passage(int index, String text, String location) {
    return new Passage(index, text, location);
  }

  private UUID insertDocument(UUID library, String fileName, List<Passage> passages) {
    io.opaa.knowledge.Document document =
        new io.opaa.knowledge.Document(
            fileName, "/" + fileName, "text/markdown", 100L, DocumentSourceType.UPLOAD);
    document.setLibraryId(library);
    document.setOrganizationId(DEFAULT_ORGANIZATION_ID);
    document.setStatus(DocumentStatus.INDEXED);
    document.setChunkCount(passages.size());
    document.setIndexedAt(Instant.now());
    documents.save(document);

    List<Document> chunks = new ArrayList<>();
    for (Passage passage : passages) {
      Map<String, Object> metadata = new LinkedHashMap<>();
      metadata.put("document_id", document.getId().toString());
      metadata.put("library_id", library.toString());
      metadata.put("file_name", fileName);
      metadata.put("chunk_index", passage.index());
      if (passage.location() != null) {
        metadata.put(ChunkingService.LOCATION_METADATA_KEY, passage.location());
      }
      chunks.add(new Document(passage.text(), metadata));
    }
    vectorStore.add(chunks);
    return document.getId();
  }

  private RequestPostProcessor devUser(String subject) {
    return request -> {
      request.addHeader(DevAuthFilter.DEV_USER_HEADER, subject);
      return request;
    };
  }
}
