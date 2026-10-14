package io.opaa.externalaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.opaa.account.LocalAccountMaintenanceStep;
import io.opaa.account.LocalTokenCleanupService;
import io.opaa.audit.AuditRetentionSettingsService;
import io.opaa.auth.DevAuthFilter;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.externalaccess.security.ExternalAccessSecurityConfig;
import io.opaa.externalaccess.token.ExternalAccessToken;
import io.opaa.externalaccess.token.ExternalAccessTokenAccountLifecycleListener;
import io.opaa.externalaccess.token.ExternalAccessTokenAuthenticator;
import io.opaa.externalaccess.token.ExternalAccessTokenAuthenticator.Result;
import io.opaa.externalaccess.token.ExternalAccessTokenExpiryReminderStep;
import io.opaa.externalaccess.token.ExternalAccessTokenLapseStep;
import io.opaa.externalaccess.token.ExternalAccessTokenRepository;
import io.opaa.externalaccess.token.ExternalAccessTokenRetentionStep;
import io.opaa.externalaccess.token.ExternalAccessTokenRevocationReason;
import io.opaa.externalaccess.token.ExternalAccessTokenValues;
import io.opaa.externalaccess.web.ExternalAccessSettingsController;
import io.opaa.externalaccess.web.ExternalAccessTokenAdminController;
import io.opaa.externalaccess.web.ExternalAccessTokenController;
import io.opaa.library.LibraryExternalAccessScheduler;
import io.opaa.mail.MailSettingsService;
import io.opaa.mcp.McpEndpoint;
import io.opaa.mcp.McpServerConfiguration;
import io.opaa.organization.Organization;
import io.opaa.security.LocalAuthKeyService;
import io.opaa.security.LocalAuthKeyService.Purpose;
import io.opaa.test.LocalMailbox;
import io.opaa.test.OpaaPropertyVariantIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.ClassUtils;

/**
 * The external-access feature switched off (ADR-0044) in a whole application: the channel does not
 * exist for the installation. No filter chain claims {@code /mcp} or a bearer access token, the
 * Spring AI starter registers no MCP server, every route of the channel answers like an unknown
 * address, no value authenticates and no expiry reminder goes out - while tokens and settings stay
 * stored as they were and their deletion periods keep running like every other.
 *
 * <p>The tokens here are real rows with a real lookup hash, i.e. values that would authenticate
 * with the feature on; the settings row has the installation switch on.
 */
@OpaaPropertyVariantIntegrationTest
class ExternalAccessSwitchedOffIntegrationTest {

  private static final String UNKNOWN_PATH = "/mcp-gibt-es-nicht";
  private static final String INITIALIZE =
      "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
          + "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
          + "\"clientInfo\":{\"name\":\"probe\",\"version\":\"1\"}}}";

  @LocalServerPort private int port;

  @Autowired private ApplicationContext context;
  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private UserRepository users;
  @Autowired private ExternalAccessTokenRepository tokens;
  @Autowired private LocalAuthKeyService keys;
  @Autowired private ExternalAccessTokenAuthenticator authenticator;
  @Autowired private ExternalAccessSettingsService settings;
  @Autowired private LocalTokenCleanupService maintenance;
  @Autowired private MailSettingsService mailSettings;
  @Autowired private AuditRetentionSettingsService retention;
  @Autowired private Clock clock;

  private final HttpClient http = HttpClient.newHttpClient();

  private UUID ownerId;
  private UUID libraryId;
  private String rawValue;
  private UUID tokenId;

  @BeforeEach
  void setUp() throws Exception {
    mockMvc.perform(get("/api/v1/auth/me").with(devUser("dev-user"))).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/auth/me").with(devUser("dev-admin"))).andExpect(status().isOk());
    ownerId = users.findBySubjectAndIssuer("dev-user", "opaa-dev").map(User::getId).orElseThrow();
    // The stored installation switch is on; SeededRowRestorer puts the seed row back afterwards.
    jdbc.update("UPDATE external_access_settings SET enabled = true");
    libraryId = insertLibrary();
    rawValue = freshValue();
    tokenId = insertToken(rawValue, clock.instant().plus(Duration.ofDays(30)));
  }

  @AfterEach
  void tearDown() {
    jdbc.update(
        "DELETE FROM audit_log WHERE object_id IN (SELECT id::text FROM external_access_tokens"
            + " WHERE user_id = ?)",
        ownerId);
    jdbc.update("DELETE FROM external_access_tokens WHERE user_id = ?", ownerId);
    jdbc.update("DELETE FROM audit_log WHERE object_id = ?", libraryId.toString());
    // Not OwnLibraryFixtures: it also clears vector_store, which this signature never creates
    // (initialize-schema=false); the library here has no documents and no chunks.
    jdbc.update("DELETE FROM asset_visibility_history WHERE asset_id = ?", libraryId);
    jdbc.update(
        "DELETE FROM asset_ownership_history WHERE asset_type = 'KNOWLEDGE_LIBRARY'"
            + " AND asset_id = ?",
        libraryId);
    jdbc.update("DELETE FROM assets WHERE id = ?", libraryId);
  }

  @Test
  void neitherTheChannelNorTheMcpServerIsRegistered() {
    for (Class<?> absent :
        List.of(
            ExternalAccessSecurityConfig.class,
            ExternalAccessPathAllowlist.class,
            ExternalAccessSettingsController.class,
            ExternalAccessTokenController.class,
            ExternalAccessTokenAdminController.class,
            McpEndpoint.class,
            McpServerConfiguration.class,
            McpStatelessSyncServer.class,
            McpSyncServer.class,
            ExternalAccessTokenExpiryReminderStep.class,
            LibraryExternalAccessScheduler.class)) {
      assertThat(context.getBeanNamesForType(absent)).as(absent.getSimpleName()).isEmpty();
    }
    assertThat(context.containsBean("libraryExternalAccessController")).isFalse();
    assertThat(context.containsBean("webMvcStatelessServerRouterFunction")).isFalse();
    assertThat(context.getBeansOfType(SecurityFilterChain.class))
        .doesNotContainKey("externalAccessSecurityFilterChain");
    assertThat(context.getBeansOfType(LocalAccountMaintenanceStep.class).values())
        .filteredOn(step -> step.getClass().getPackageName().startsWith("io.opaa.externalaccess"))
        .extracting(step -> ClassUtils.getUserClass(step).getSimpleName())
        .containsExactlyInAnyOrder(
            ExternalAccessTokenLapseStep.class.getSimpleName(),
            ExternalAccessTokenRetentionStep.class.getSimpleName());
  }

  @Test
  void theBeansOthersDependOnStayAndTheAccountLifecycleStillEndsTokens() {
    assertThat(context.getBeanNamesForType(ExternalAccessSettingsService.class)).hasSize(1);
    assertThat(context.getBeanNamesForType(ExternalAccessTokenAuthenticator.class)).hasSize(1);
    assertThat(context.getBeanNamesForType(ExternalAccessTokenAccountLifecycleListener.class))
        .hasSize(1);
  }

  @Test
  void theMcpEndpointAnswersLikeAnUnknownAddressEvenToAWorkingToken() {
    for (String bearer : new String[] {rawValue, null}) {
      HttpResponse<String> mcp = send("POST", "/mcp", bearer);
      HttpResponse<String> unknown = send("POST", UNKNOWN_PATH, bearer);
      assertThat(mcp.statusCode()).isEqualTo(unknown.statusCode()).isEqualTo(404);
      assertThat(normalized(mcp.body(), "/mcp"))
          .isEqualTo(normalized(unknown.body(), UNKNOWN_PATH));
      assertThat(mcp.body()).doesNotContain("jsonrpc");

      assertThat(send("GET", "/mcp", bearer).statusCode())
          .isEqualTo(send("GET", UNKNOWN_PATH, bearer).statusCode());
    }
  }

  @Test
  void noValueAuthenticatesAndTheChannelReadsClosedThoughTheStoredSwitchIsOn() {
    assertThat(authenticator.authenticate(rawValue)).isInstanceOf(Result.Refused.class);
    assertThat(authenticator.authenticateWithSwitchLast(rawValue))
        .isInstanceOf(Result.Refused.class);
    assertThat(settings.isEnabled()).isFalse();
    assertThat(settings.current().values().enabled()).isTrue();
  }

  @Test
  void everyRouteOfTheChannelAnswersLikeAnUnknownAddress() throws Exception {
    int unknown = statusOf(get("/api/v1/gibt-es-nicht"), "dev-admin");
    assertThat(unknown).isEqualTo(404);
    String token = "/api/v1/admin/external-access/tokens";
    List<Supplier<MockHttpServletRequestBuilder>> routes =
        List.of(
            () -> get("/api/v1/system/external-access"),
            () -> put("/api/v1/system/external-access").content("{}"),
            () -> get("/api/v1/external-access/settings"),
            () -> get("/api/v1/external-access/tokens"),
            () -> post("/api/v1/external-access/tokens").content("{}"),
            () -> get("/api/v1/external-access/eligible-libraries"),
            () -> delete("/api/v1/external-access/tokens/" + tokenId),
            () -> get(token),
            () -> post(token + "/" + tokenId + "/block").content("{}"),
            () -> post(token + "/block-by-owner").content("{}"),
            () -> get("/api/v1/admin/external-access/libraries"),
            () ->
                put("/api/v1/libraries/" + libraryId + "/external-access")
                    .content("{\"enabled\":false}"));
    for (Supplier<MockHttpServletRequestBuilder> route : routes) {
      assertThat(statusOf(route.get(), "dev-admin")).isEqualTo(unknown);
      assertThat(statusOf(route.get(), "dev-user")).isEqualTo(unknown);
    }
  }

  @Test
  void theInstallationReportsTheFeatureOffAndTheLibraryDetailNamesNoRelease() throws Exception {
    mockMvc
        .perform(get("/api/v1/auth/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.features.externalAccess").value(false));
    mockMvc
        .perform(get("/api/v1/libraries/" + libraryId).with(devUser("dev-user")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.myRole").value("OWNER"))
        .andExpect(jsonPath("$.externalAccess").doesNotExist());
  }

  /**
   * Switching the feature off is no way to keep token rows: the lapse and the deletion period run
   * on as with the feature on (ADR-0044, Entscheidung 3). Only the reminder mail pauses.
   */
  @Test
  void theDeletionPeriodsRunOnAndNoReminderGoesOut() {
    User owner = users.findById(ownerId).orElseThrow();
    assertThat(owner.getEmail()).as("a reminder would have an address").isNotBlank();
    ZoneId zone = clock.getZone();
    UUID expiringSoon =
        insertToken(
            freshValue(), LocalDate.now(clock).plusDays(3).atTime(12, 0).atZone(zone).toInstant());
    UUID expired = insertToken(freshValue(), clock.instant().minus(Duration.ofDays(1)));
    UUID longGone = insertToken(freshValue(), clock.instant().minus(Duration.ofDays(2)));
    UUID adminId =
        users.findBySubjectAndIssuer("dev-admin", "opaa-dev").map(User::getId).orElseThrow();
    LocalMailbox mailbox = new LocalMailbox(mailSettings, adminId);
    mailbox.start();
    try {
      maintenance.runOnce();
      jdbc.update(
          "UPDATE external_access_tokens SET lapse_recorded_at = ? WHERE id = ?",
          Timestamp.from(
              clock
                  .instant()
                  .minus(Duration.ofDays(32L * retention.currentRetentionMonths() + 30))),
          longGone);
      maintenance.runOnce();

      assertThat(mailbox.waitFor(1, 2000)).as("no expiry reminder").isFalse();
    } finally {
      mailbox.stop();
    }

    ExternalAccessToken lapsed = tokens.findById(expired).orElseThrow();
    assertThat(lapsed.getRevocationReason()).isEqualTo(ExternalAccessTokenRevocationReason.EXPIRED);
    assertThat(lapsed.getLapseRecordedAt()).isNotNull();
    assertThat(tokens.findById(longGone)).isEmpty();
    assertThat(tokens.findById(expiringSoon).orElseThrow().getRevokedAt()).isNull();
    assertThat(tokens.findById(tokenId).orElseThrow().getRevokedAt()).isNull();
  }

  private int statusOf(MockHttpServletRequestBuilder request, String devUser) throws Exception {
    MvcResult result =
        mockMvc
            .perform(request.contentType(MediaType.APPLICATION_JSON).with(devUser(devUser)))
            .andReturn();
    return result.getResponse().getStatus();
  }

  private HttpResponse<String> send(String method, String path, String bearer) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .method(
                method,
                "POST".equals(method)
                    ? HttpRequest.BodyPublishers.ofString(INITIALIZE, StandardCharsets.UTF_8)
                    : HttpRequest.BodyPublishers.noBody());
    if (bearer != null) {
      builder.header("Authorization", "Bearer " + bearer);
    }
    try {
      return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** The error body without what differs between any two requests: the path and the time. */
  private static String normalized(String body, String path) {
    return body.replace(path, "<path>").replaceAll("\"timestamp\"\\s*:\\s*\"[^\"]*\"", "");
  }

  private static String freshValue() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return ExternalAccessTokenValues.VALUE_PREFIX
        + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private UUID insertToken(String value, Instant expiresAt) {
    Instant createdAt = expiresAt.minus(Duration.ofDays(60));
    return tokens
        .save(
            new ExternalAccessToken(
                ownerId,
                "Abgeschaltet",
                value.substring(0, ExternalAccessTokenValues.VALUE_PREFIX.length() + 6),
                keys.lookupHash(Purpose.EXTERNAL_ACCESS_TOKEN_LOOKUP, value),
                createdAt,
                expiresAt,
                List.of(libraryId)))
        .getId();
  }

  private UUID insertLibrary() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "WITH shell AS (INSERT INTO assets (id, asset_type, organization_id, name, owner_type,"
            + " owner_user_id) VALUES (?, 'KNOWLEDGE_LIBRARY', ?, ?, 'USER', ?)"
            + " RETURNING id, organization_id) INSERT INTO knowledge_libraries (id,"
            + " organization_id, source_type) SELECT id, organization_id, 'UPLOAD' FROM shell",
        id,
        Organization.DEFAULT_ID,
        "Abgeschaltete Freigabe",
        ownerId);
    jdbc.update(
        "INSERT INTO asset_grants (id, asset_type, asset_id, organization_id, subject_type,"
            + " subject_user_id, role, created_at, updated_at) VALUES (?, 'KNOWLEDGE_LIBRARY', ?,"
            + " ?, 'USER', ?, 'OWNER', now(), now())",
        UUID.randomUUID(),
        id,
        Organization.DEFAULT_ID,
        ownerId);
    return id;
  }

  private static org.springframework.test.web.servlet.request.RequestPostProcessor devUser(
      String subject) {
    return request -> {
      request.addHeader(DevAuthFilter.DEV_USER_HEADER, subject);
      return request;
    };
  }
}
