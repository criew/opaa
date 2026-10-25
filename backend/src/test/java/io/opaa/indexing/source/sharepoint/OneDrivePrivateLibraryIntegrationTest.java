package io.opaa.indexing.source.sharepoint;

import static io.opaa.test.ProfileLibraries.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import io.opaa.api.types.ConnectionAuthorizationPurpose;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.connection.oauth.AuthorizationsAtBaseUrl;
import io.opaa.connection.oauth.ConnectionAuthorizationService;
import io.opaa.msgraph.FakeGraphServer;
import io.opaa.organization.Organization;
import io.opaa.test.ConnectorReleases;
import io.opaa.test.FakeAuthorizationServer;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * OneDrive as a person's connected account in the wired application (ADR-0058), against the shared
 * fake Graph and authorization server ({@link OneDriveTestSeam}): the administration creates the
 * profile, Anna consents with PKCE, her private library on her OneDrive is indexed and found by her
 * alone; a library naming another OneDrive and an account switched to another Microsoft account
 * read no drive at all; a refused refresh lets the library rest; and no token, code, secret,
 * address or file name reaches a log line, every application logger read at DEBUG.
 */
@OpaaIntegrationTest
class OneDrivePrivateLibraryIntegrationTest {

  private static final FakeAuthorizationServer PROVIDER = FakeAuthorizationServer.shared();
  private static final FakeGraphServer GRAPH = FakeGraphServer.shared();

  private static final String OWNER = "dev-user";
  private static final String OTHER = "dev-admin";
  private static final String LIBRARIES = "/api/v1/libraries";
  private static final String BASE = "https://opaa.example.org";
  private static final String TENANT = "contoso.onmicrosoft.com";
  private static final String CLIENT_ID = "opaa-onedrive-2467";
  private static final String CLIENT_SECRET = "client-secret-2467-geheim";

  /** Part of every folder and file name; never part of an identifier or a message. */
  private static final String MARKER = "Geheim";

  private static final String ANNA = "anna.amsel@contoso.example";
  private static final String BERND = "bernd.brandt@contoso.example";
  private static final String DRIVE_ANNA = "b!onedrive-anna-2467";
  private static final String DRIVE_BERND = "b!onedrive-bernd-2467";
  private static final String QUESTION = "Wie hoch ist der Haushaltsansatz des Wasserwerks?";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OwnLibraryFixtures fixtures;
  @Autowired private ApplicationContext context;

  private final List<UUID> libraries = new ArrayList<>();
  private final List<String> sensitive = new ArrayList<>();
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private Logger root;
  private Logger application;
  private Level previousLevel;
  private UUID profile;
  private UUID person;
  private ConnectionAuthorizationService service;

  @BeforeEach
  void aProfileForPersonsAndTwoOneDrives() throws Exception {
    PROVIDER.reset();
    GRAPH.accountsOf(PROVIDER::accountOf);
    GRAPH.oneDriveOf(ANNA, DRIVE_ANNA, "business", ANNA, "Anna Amsel");
    GRAPH.oneDriveOf(BERND, DRIVE_BERND, "business", BERND, "Bernd Brandt");
    String rootAnna = FakeGraphServer.rootId(DRIVE_ANNA);
    GRAPH.folder(DRIVE_ANNA, "ordner", "Haushalt-Geheim", rootAnna);
    GRAPH.file(
        DRIVE_ANNA,
        "plan",
        "Haushaltsplan-Geheim.txt",
        "ordner",
        bytes("Der Haushaltsansatz des Wasserwerks beträgt 4,2 Millionen Euro."));
    GRAPH.file(
        DRIVE_ANNA, "notiz", "Notiz-Geheim.txt", rootAnna, bytes("Die Sitzung beginnt um neun."));
    GRAPH.file(
        DRIVE_BERND,
        "bernd",
        "Bernd-Geheim.txt",
        FakeGraphServer.rootId(DRIVE_BERND),
        bytes("Bernds eigene Akte."));
    sensitive.addAll(List.of(CLIENT_SECRET, ANNA, BERND, GRAPH.presignedSecret()));

    root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    application = (Logger) LoggerFactory.getLogger("io.opaa");
    previousLevel = application.getLevel();
    application.setLevel(Level.DEBUG);
    // the run's download and HTTP threads log while the test reads the list
    appender.list = new CopyOnWriteArrayList<>();
    appender.start();
    root.addAppender(appender);

    body(mockMvc.perform(as(OWNER, get("/api/v1/spaces"))));
    body(mockMvc.perform(as(OTHER, get("/api/v1/spaces"))));
    person = userIdOf("dev-user@opaa.local");
    String created =
        body(
            mockMvc
                .perform(
                    as(OTHER, post("/api/v1/admin/connection-profiles"))
                        .content(
                            """
                            {"name": "Zugang OneDrive %s", "sourceType": "ONEDRIVE",
                             "serverUrl": "", "authMethod": "OAUTH", "ownership": "PERSON",
                             "clientId": "%s", "clientSecret": "%s", "tenant": "%s"}
                            """
                                .formatted(UUID.randomUUID(), CLIENT_ID, CLIENT_SECRET, TENANT)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.serverUrl").value(GRAPH.origin().toString())));
    profile = UUID.fromString(JsonPath.read(created, "$.id"));
    ConnectorReleases.releaseToAllAccounts(jdbc, "PROFILE:" + profile);
    service = AuthorizationsAtBaseUrl.service(context, BASE);
  }

  @AfterEach
  void removeOwnRows() {
    root.detachAppender(appender);
    application.setLevel(previousLevel);
    PROVIDER.reset();
    for (UUID library : libraries) {
      jdbc.update("DELETE FROM audit_log WHERE object_id = ?", library.toString());
      jdbc.update("DELETE FROM asset_grant_history WHERE asset_id = ?", library);
      jdbc.update("DELETE FROM notifications WHERE object_id = ?", library);
    }
    fixtures.removeLibraries(libraries.toArray(UUID[]::new));
    if (profile != null) {
      jdbc.update("DELETE FROM connection_authorizations WHERE profile_id = ?", profile);
      jdbc.update("DELETE FROM connected_accounts WHERE profile_id = ?", profile);
      jdbc.update("DELETE FROM connection_log WHERE profile_id = ?", profile);
      jdbc.update("DELETE FROM notifications WHERE object_id = ?", profile);
      ConnectorReleases.withdraw(jdbc, "PROFILE:" + profile);
      jdbc.update("DELETE FROM audit_log WHERE object_id = ?", profile.toString());
      jdbc.update("DELETE FROM connection_profiles WHERE id = ?", profile);
    }
  }

  @Test
  void herConsentConnectsHerOneDriveAndOnlySheFindsWhatItsRunIndexed() throws Exception {
    String completed = connectAs(ANNA);

    assertThat(JsonPath.<String>read(completed, "$.account.state")).isEqualTo("CONNECTED");
    assertThat(JsonPath.<String>read(completed, "$.account.accountLabel"))
        .as("the owner me/drive names for her token")
        .isEqualTo(ANNA);
    FakeAuthorizationServer.Request exchange = PROVIDER.requests("authorization_code").getFirst();
    assertThat(exchange.target().getPath()).isEqualTo(OneDriveTestSeam.TOKEN_PATH + TENANT);
    assertThat(exchange.form())
        .containsEntry("client_id", CLIENT_ID)
        .containsEntry("client_secret", CLIENT_SECRET)
        .containsEntry("redirect_uri", BASE + "/connections/callback")
        .containsKey("code_verifier");
    assertThat(exchange.authorization()).as("client_secret_post").isNull();

    UUID library = createPrivateLibrary(DRIVE_ANNA);
    String status = run(library);

    assertThat(JsonPath.<String>read(status, "$.status")).as(status).isEqualTo("COMPLETED");
    assertThat(JsonPath.<Integer>read(status, "$.documentsIndexedTotal")).as(status).isEqualTo(2);
    assertThat(GRAPH.tokens()).as("only her token reached Graph").containsOnly(lastAccessToken());
    assertThat(OneDriveTestDrives.touched(GRAPH, DRIVE_BERND)).isFalse();
    assertThat(
            jdbc.queryForList(
                "SELECT file_path FROM documents WHERE library_id = ? ORDER BY file_path",
                String.class,
                library))
        .containsExactly(
            "onedrive://" + DRIVE_ANNA + "/notiz", "onedrive://" + DRIVE_ANNA + "/plan");
    UUID plan = documentOf(library, "plan");

    assertThat(searchHits(OWNER, List.of(library))).contains(plan.toString());
    mockMvc
        .perform(as(OWNER, get("/api/v1/documents/" + plan + "/content")))
        .andExpect(status().isOk());

    UUID unknown = UUID.randomUUID();
    mockMvc.perform(as(OTHER, get(LIBRARIES + "/" + library))).andExpect(status().isNotFound());
    mockMvc
        .perform(as(OTHER, get("/api/v1/documents/" + plan + "/content")))
        .andExpect(status().isNotFound());
    assertThat(searchHits(OTHER, List.of(library))).isEmpty();
    assertThat(searchAnswer(OTHER, List.of(library)))
        .as("searching her library answers as an unknown one does")
        .isEqualTo(searchAnswer(OTHER, List.of(unknown)));
    assertThat(searchHits(OWNER, List.of())).contains(plan.toString());
    assertThat(searchHits(OTHER, List.of())).doesNotContain(plan.toString());
    assertNothingLeaked();
  }

  @Test
  void aLibraryNamingAnotherOneDriveFailsWithoutAnyRequestToIt() throws Exception {
    connectAs(ANNA);
    UUID library = createPrivateLibrary(DRIVE_BERND);

    String status = run(library);

    assertThat(JsonPath.<String>read(status, "$.status")).as(status).isEqualTo("FAILED");
    assertThat(errorOfLastRun(library)).isEqualTo(OwnDrive.OTHER_DRIVE);
    assertThat(OneDriveTestDrives.touched(GRAPH, DRIVE_BERND)).isFalse();
    assertThat(GRAPH.requests()).allSatisfy(request -> assertThat(request).contains("me/drive"));
    assertThat(documentCount(library)).isZero();
    assertNothingLeaked();
  }

  @Test
  void reconnectedAsAnotherMicrosoftAccountTheRunReadsNeitherOneDrive() throws Exception {
    connectAs(ANNA);
    UUID library = createPrivateLibrary(DRIVE_ANNA);
    assertThat(JsonPath.<String>read(run(library), "$.status")).isEqualTo("COMPLETED");
    connectAs(BERND);
    int before = GRAPH.requests().size();

    String status = run(library);

    assertThat(JsonPath.<String>read(status, "$.status")).as(status).isEqualTo("FAILED");
    assertThat(errorOfLastRun(library)).isEqualTo(OwnDrive.OTHER_DRIVE);
    List<String> requests = GRAPH.requests();
    assertThat(requests.subList(before, requests.size()))
        .isNotEmpty()
        .allSatisfy(request -> assertThat(request).contains("me/drive"));
    assertThat(documentCount(library)).as("a refused run removes nothing").isEqualTo(2);
    assertNothingLeaked();
  }

  @Test
  void aRefreshTheProviderRefusesLetsTheLibraryRestWithTheHintToReconnect() throws Exception {
    connectAs(ANNA);
    UUID library = createPrivateLibrary(DRIVE_ANNA);
    assertThat(JsonPath.<String>read(run(library), "$.status")).isEqualTo("COMPLETED");
    nearItsEnd();
    PROVIDER.rejectWith(400, "invalid_grant");
    int graphRequests = GRAPH.requests().size();

    String status = run(library);

    assertThat(JsonPath.<String>read(status, "$.status")).as(status).isEqualTo("FAILED");
    assertThat(PROVIDER.requests("refresh_token")).hasSize(1);
    assertThat(GRAPH.requests()).as("no request without a token").hasSize(graphRequests);
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM connected_accounts WHERE user_id = ? AND profile_id = ?",
                String.class,
                person,
                profile))
        .isEqualTo("EXPIRED");
    mockMvc
        .perform(as(OWNER, get(LIBRARIES + "/" + library)))
        .andExpect(jsonPath("$.sourceBlock.reason").value("EXPIRED"))
        .andExpect(jsonPath("$.sourceBlock.action").value("CONNECT_OWN_ACCOUNT"));
    assertThat(documentCount(library)).as("the resting library keeps its documents").isEqualTo(2);
    assertNothingLeaked();
  }

  /**
   * Runs - a full one, a change run after a new, a changed and a removed file -, an original and
   * the erasure of her library log none of the names of her OneDrive.
   */
  @Test
  void runsOriginalsAndErasureLogNoNameOfHerOneDrive() throws Exception {
    connectAs(ANNA);
    UUID library = createPrivateLibrary(DRIVE_ANNA);
    assertThat(JsonPath.<String>read(run(library), "$.status")).isEqualTo("COMPLETED");
    GRAPH.file(
        DRIVE_ANNA, "neu", "Vermerk-Geheim.txt", "ordner", bytes("Ein neuer Vermerk zur Akte."));
    GRAPH.update("plan", bytes("Der Haushaltsansatz wurde auf 4,5 Millionen Euro erhöht."));
    GRAPH.delete("notiz");
    GRAPH.file(DRIVE_ANNA, "fremd", "Format-Geheim.xyz", "ordner", bytes("Kein Format."));
    assertThat(JsonPath.<String>read(run(library), "$.status")).isEqualTo("COMPLETED");
    assertThat(documentCount(library)).isEqualTo(2);
    mockMvc
        .perform(as(OWNER, get("/api/v1/documents/" + documentOf(library, "neu") + "/content")))
        .andExpect(status().isOk());
    mockMvc
        .perform(as(OWNER, delete(LIBRARIES + "/" + library)))
        .andExpect(result -> assertThat(result.getResponse().getStatus()).isLessThan(300));
    await()
        .atMost(Duration.ofSeconds(30))
        .until(
            () ->
                jdbc.queryForObject(
                        "SELECT count(*) FROM knowledge_libraries WHERE id = ?",
                        Integer.class,
                        library)
                    == 0);

    assertThat(appender.list).isNotEmpty();
    assertNothingLeaked();
  }

  /** Starts her consent, lets the provider approve it as {@code account} and completes it. */
  private String connectAs(String account) throws Exception {
    PROVIDER.account(account);
    CurrentUser caller =
        CurrentUser.of(person, Organization.DEFAULT_ID, SystemRole.USER, "Dev User");
    URI url =
        service.start(caller, profile, ConnectionAuthorizationPurpose.ACCOUNT).authorizationUrl();
    assertThat(url.toString()).startsWith(PROVIDER.issuer() + "/" + TENANT + "/authorize?");
    Map<String, String> query = query(url);
    assertThat(query)
        .containsEntry("client_id", CLIENT_ID)
        .containsEntry("scope", OneDriveSourceConnector.SCOPES)
        .containsEntry("prompt", "select_account");
    String code = PROVIDER.approve(url);
    sensitive.addAll(List.of(code, query.get("state")));
    String completed =
        body(
            mockMvc
                .perform(
                    as(OWNER, post("/api/v1/connections/authorizations/complete"))
                        .content(
                            "{\"state\": \"%s\", \"code\": \"%s\"}"
                                .formatted(query.get("state"), code)))
                .andExpect(status().isOk()));
    for (FakeAuthorizationServer.Request request : PROVIDER.requests("authorization_code")) {
      sensitive.add(request.form().get("code_verifier"));
    }
    sensitive.addAll(List.of(PROVIDER.lastToken(), PROVIDER.lastRefreshToken()));
    return completed;
  }

  private String lastAccessToken() {
    return PROVIDER.lastToken();
  }

  private UUID createPrivateLibrary(String driveId) throws Exception {
    String created =
        body(
            mockMvc
                .perform(
                    as(OWNER, post(LIBRARIES))
                        .content(
                            """
                            {"name": "Mein OneDrive %s", "sourceType": "ONEDRIVE",
                             "privateLibrary": true, "connectionProfileId": "%s",
                             "sourceSettings": {"drive": {"driveId": "%s"}}}
                            """
                                .formatted(UUID.randomUUID(), profile, driveId)))
                .andExpect(status().isCreated()));
    UUID library = UUID.fromString(JsonPath.read(created, "$.id"));
    libraries.add(library);
    return library;
  }

  private String run(UUID library) throws Exception {
    int before = runsOf(library);
    mockMvc
        .perform(as(OWNER, post(LIBRARIES + "/" + library + "/indexing")))
        .andExpect(status().isAccepted());
    String[] status = new String[1];
    await()
        .atMost(Duration.ofSeconds(60))
        .until(
            () -> {
              status[0] =
                  body(
                      mockMvc.perform(
                          as(OWNER, get(LIBRARIES + "/" + library + "/indexing/status"))));
              String state = JsonPath.read(status[0], "$.status");
              return runsOf(library) > before
                  && (state.equals("COMPLETED") || state.equals("FAILED"));
            });
    return status[0];
  }

  /** The {@code documentId} of every hit {@code user} gets for {@link #QUESTION}. */
  private List<String> searchHits(String user, List<UUID> libraryIds) throws Exception {
    String answer =
        body(
            mockMvc
                .perform(as(user, post("/api/v1/search")).content(searchRequest(libraryIds)))
                .andExpect(status().isOk()));
    return JsonPath.read(answer, "$.hits[*].documentId");
  }

  /** Status and body of the answer {@code user} gets for {@link #QUESTION}. */
  private String searchAnswer(String user, List<UUID> libraryIds) throws Exception {
    var response =
        mockMvc
            .perform(as(user, post("/api/v1/search")).content(searchRequest(libraryIds)))
            .andReturn()
            .getResponse();
    return response.getStatus() + " " + response.getContentAsString(StandardCharsets.UTF_8);
  }

  private static String searchRequest(List<UUID> libraryIds) {
    String ids = String.join(", ", libraryIds.stream().map(id -> "\"" + id + "\"").toList());
    return libraryIds.isEmpty()
        ? "{\"question\": \"%s\"}".formatted(QUESTION)
        : "{\"question\": \"%s\", \"libraryIds\": [%s]}".formatted(QUESTION, ids);
  }

  /** The stored access token lasts one more minute of an hour: within the renewal margin. */
  private void nearItsEnd() {
    jdbc.update(
        "UPDATE connection_tokens SET access_token_expires_at = now() + interval '1 minute',"
            + " updated_at = now() - interval '1 hour' WHERE profile_id = ?",
        profile);
  }

  /** No token, code, verifier, state, secret or address in a log line, no name of her files. */
  private void assertNothingLeaked() {
    List<String> lines = new ArrayList<>();
    for (ILoggingEvent event : List.copyOf(appender.list)) {
      lines.add(
          event.getLoggerName()
              + ": "
              + event.getFormattedMessage()
              + (event.getThrowableProxy() == null
                  ? ""
                  : "\n" + ThrowableProxyUtil.asString(event.getThrowableProxy())));
    }
    assertThat(lines).noneSatisfy(line -> assertThat(line).contains(MARKER));
    for (String value : sensitive) {
      if (value != null) {
        assertThat(lines).noneSatisfy(line -> assertThat(line).contains(value));
      }
    }
  }

  private String errorOfLastRun(UUID library) {
    return jdbc.queryForObject(
        "SELECT error_message FROM indexing_jobs WHERE library_id = ?"
            + " ORDER BY started_at DESC LIMIT 1",
        String.class,
        library);
  }

  private int runsOf(UUID library) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM indexing_jobs WHERE library_id = ?", Integer.class, library);
  }

  private int documentCount(UUID library) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM documents WHERE library_id = ?", Integer.class, library);
  }

  private UUID documentOf(UUID library, String itemId) {
    return jdbc.queryForObject(
        "SELECT id FROM documents WHERE library_id = ? AND file_path = ?",
        UUID.class,
        library,
        "onedrive://" + DRIVE_ANNA + "/" + itemId);
  }

  private UUID userIdOf(String email) {
    return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
  }

  private static Map<String, String> query(URI url) {
    Map<String, String> query = new LinkedHashMap<>();
    for (String pair : url.getRawQuery().split("&")) {
      int equals = pair.indexOf('=');
      query.put(
          URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
          URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
    }
    return query;
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static String body(ResultActions result) throws Exception {
    return result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
  }
}
