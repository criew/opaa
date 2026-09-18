package io.opaa.externalaccess.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.LibraryOwnerType;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.DevAuthFilter;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.externalaccess.ExternalAccessSettings;
import io.opaa.externalaccess.ExternalAccessSettingsService;
import io.opaa.library.KnowledgeLibraryService;
import io.opaa.library.LibraryCreation;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * What a bearer access token reaches and what it is refused with (#1718, ADR-0035).
 *
 * <p>Runs under the canonical signature although it drives a bearer token: the channel's filter
 * chain matches on the <em>value</em>, not on a path or a profile, and is therefore ordered ahead
 * of both the {@code oidc} and the {@code dev} chain - {@code DevAuthFilter} never sees such a
 * request. That is the property under test here, so a second context family would not only be
 * unnecessary but would test something else.
 *
 * <p>The last test is the log-capture regression guard of the prefix rule: over months the prefix
 * is stable and attributable to one person, so together with a timestamp in a technical log it
 * would be the per-person query history the audit trail excludes - only without its protections.
 */
@OpaaIntegrationTest
class ExternalAccessTokenAuthenticationIntegrationTest {

  private static final String[] REFUSED_ENDPOINT_FAMILIES = {
    "/api/v1/admin/external-access/tokens",
    "/api/v1/libraries",
    "/api/v1/spaces",
    "/api/v1/admin/indexing/pipeline-versions",
    "/api/v1/notifications"
  };

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private OwnLibraryFixtures ownLibraryFixtures;
  @Autowired private UserRepository users;
  @Autowired private KnowledgeLibraryService libraryService;
  @Autowired private ExternalAccessTokenRepository tokens;
  @Autowired private ExternalAccessSettingsService settings;
  @Autowired private Clock clock;

  private User owner;
  private User administrator;
  private UUID libraryId;
  private String rawValue;
  private UUID tokenId;

  @BeforeEach
  void setUp() throws Exception {
    mockMvc.perform(get("/api/v1/auth/me").with(devUser())).andExpect(status().isOk());
    owner = users.findBySubjectAndIssuer("dev-user", "opaa-dev").orElseThrow();
    mockMvc.perform(get("/api/v1/auth/me").with(devUser("dev-admin"))).andExpect(status().isOk());
    administrator = users.findBySubjectAndIssuer("dev-admin", "opaa-dev").orElseThrow();
    setChannelEnabled(true);
    libraryId =
        libraryService
            .createLibrary(
                new LibraryCreation(
                    "Bearer-Testbibliothek",
                    null,
                    LibraryOwnerType.USER,
                    owner.getId(),
                    LibraryVisibility.PRIVATE,
                    false,
                    DocumentSourceType.UPLOAD,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null),
                CurrentUser.of(
                    owner.getId(), owner.getOrganizationId(), owner.getSystemRole(), "x"))
            .library()
            .getId();
    removeOwnTokens();
    String body =
        mockMvc
            .perform(
                post("/api/v1/external-access/tokens")
                    .with(devUser())
                    .content(
                        "{\"name\":\"Bearer\",\"libraryIds\":[\""
                            + libraryId
                            + "\"],\"expiresAt\":\""
                            + clock.instant().plus(Duration.ofDays(30))
                            + "\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    rawValue = JsonPath.read(body, "$.token");
    tokenId = UUID.fromString(JsonPath.read(body, "$.id"));
  }

  /**
   * The installation switch of #1717. The settings row is installation-wide, so {@code
   * SeededRowRestorer} puts it back after every method.
   */
  private void setChannelEnabled(boolean enabled) {
    ExternalAccessSettings.Values values = settings.current().values();
    settings.update(
        CurrentUser.of(
            administrator.getId(),
            administrator.getOrganizationId(),
            administrator.getSystemRole(),
            "Systemverwaltung"),
        new ExternalAccessSettingsService.Update(
            enabled,
            values.tokenMaxLifetimeDays(),
            values.tokenRateLimitPerHour(),
            values.allowedCidrs(),
            values.massRetrievalAlertThreshold(),
            values.serverInstructions()));
  }

  @AfterEach
  void tearDown() {
    removeOwnTokens();
    ownLibraryFixtures.removeLibraries(libraryId);
  }

  private void removeOwnTokens() {
    jdbcTemplate.update("DELETE FROM external_access_tokens WHERE user_id = ?", owner.getId());
  }

  private RequestPostProcessor devUser() {
    return devUser("dev-user");
  }

  private RequestPostProcessor devUser(String subject) {
    return request -> {
      request.addHeader(DevAuthFilter.DEV_USER_HEADER, subject);
      request.setContentType(MediaType.APPLICATION_JSON_VALUE);
      return request;
    };
  }

  private RequestPostProcessor bearer(String value) {
    return request -> {
      request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + value);
      return request;
    };
  }

  @Test
  void aValidTokenReachesNoManagementIndexingUploadOrRightsEndpoint() throws Exception {
    for (String path : REFUSED_ENDPOINT_FAMILIES) {
      mockMvc.perform(get(path).with(bearer(rawValue))).andExpect(status().isForbidden());
    }
  }

  @Test
  void aValidTokenIsRefusedWithoutBeingAuthenticatedAsTheDevelopmentUser() throws Exception {
    // Under local,dev every request without this chain would be the development user - a 403
    // rather than a 200 proves the channel's chain took the request first.
    mockMvc
        .perform(get("/api/v1/auth/me").with(bearer(rawValue)))
        .andExpect(status().isForbidden());
  }

  @Test
  void anUnknownValueIsRefusedWithItsReason() throws Exception {
    assertRefusedWith(ExternalAccessTokenValues.VALUE_PREFIX + "nichtvergeben", "invalid_token");
  }

  @Test
  void aRevokedTokenIsRefusedWithItsReason() throws Exception {
    ExternalAccessToken token = tokens.findById(tokenId).orElseThrow();
    token.revoke(ExternalAccessTokenRevocationReason.OWNER, clock.instant());
    tokens.save(token);

    assertRefusedWith(rawValue, "token_revoked");
  }

  @Test
  void anExpiredTokenIsRefusedWithItsReason() throws Exception {
    jdbcTemplate.update(
        "UPDATE external_access_tokens SET expires_at = now() - interval '1 minute' WHERE id = ?",
        tokenId);

    assertRefusedWith(rawValue, "token_expired");
  }

  @Test
  void aBlockedTokenIsRefusedWithItsReason() throws Exception {
    ExternalAccessToken token = tokens.findById(tokenId).orElseThrow();
    token.revoke(ExternalAccessTokenRevocationReason.ADMIN, clock.instant());
    tokens.save(token);

    assertRefusedWith(rawValue, "token_revoked");
  }

  @Test
  void aTokenOfADeletedAccountNoLongerExists() {
    jdbcTemplate.update("DELETE FROM external_access_tokens WHERE id = ?", tokenId);

    assertThat(tokens.findByTokenLookupHash("whatever")).isEmpty();
    assertThat(tokens.findById(tokenId)).isEmpty();
  }

  private void assertRefusedWith(String value, String marker) throws Exception {
    mockMvc
        .perform(get("/api/v1/libraries").with(bearer(value)))
        .andExpect(status().isUnauthorized())
        .andExpect(
            result ->
                assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE))
                    .contains("error_description=\"" + marker + "\""));
  }

  @Test
  void thePrefixAppearsInNoLogLineOfTheApplication() throws Exception {
    Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    Logger application = (Logger) LoggerFactory.getLogger("io.opaa");
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    // The appender sits on the root logger, so a framework line at its configured level is caught
    // too; only OPAA's own loggers are turned up to TRACE. Turning the whole tree to TRACE would
    // instead assert something the rule never claimed: Hibernate's TRACE entity printer prints
    // every column of every entity it flushes, in no deployment this project ships.
    Level previousApplicationLevel = application.getLevel();
    application.setLevel(Level.TRACE);
    root.addAppender(appender);
    try {
      // Issue, use, refuse and an error case: the four ways the value passes through the process.
      mockMvc
          .perform(
              post("/api/v1/external-access/tokens")
                  .with(devUser())
                  .content(
                      "{\"name\":\"Protokollprobe\",\"libraryIds\":[\""
                          + libraryId
                          + "\"],\"expiresAt\":\""
                          + clock.instant().plus(Duration.ofDays(5))
                          + "\"}"))
          .andExpect(status().isCreated());
      mockMvc.perform(get("/api/v1/libraries").with(bearer(rawValue)));
      mockMvc.perform(get("/api/v1/spaces").with(bearer(rawValue)));
      mockMvc.perform(
          get("/api/v1/libraries").with(bearer(ExternalAccessTokenValues.VALUE_PREFIX + "kaputt")));
      mockMvc.perform(get("/api/v1/libraries/not-a-uuid").with(bearer(rawValue)));
    } finally {
      root.detachAppender(appender);
      application.setLevel(previousApplicationLevel);
      appender.stop();
    }

    String prefix = tokens.findById(tokenId).orElseThrow().getTokenPrefix();
    List<String> offending =
        appender.list.stream()
            .map(
                event ->
                    event.getFormattedMessage() + " " + String.valueOf(event.getThrowableProxy()))
            .filter(
                line ->
                    line.contains(prefix)
                        || line.contains(rawValue)
                        || line.contains(ExternalAccessTokenValues.VALUE_PREFIX))
            .toList();

    assertThat(offending).isEmpty();
  }
}
