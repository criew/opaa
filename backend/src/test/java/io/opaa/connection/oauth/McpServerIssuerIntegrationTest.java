package io.opaa.connection.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import io.opaa.api.types.ConnectionAuthorizationPurpose;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.DevAuthFilter;
import io.opaa.common.PublicBaseUrl;
import io.opaa.common.PublicBaseUrlProperties;
import io.opaa.connection.account.ConnectedAccountService;
import io.opaa.connection.consent.SourceConsentService;
import io.opaa.connection.profile.ConnectionProfileRepository;
import io.opaa.connection.profile.EffectiveSourceSettings;
import io.opaa.connection.profile.ProfileRegistrations;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.organization.Organization;
import io.opaa.security.CredentialsEncryptor;
import io.opaa.security.TargetAddressValidator;
import io.opaa.test.FakeMcpServer;
import io.opaa.test.OpaaIntegrationTest;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The mix-up protection of the consent (RFC 9207, #2266) against two fake MCP servers: a response
 * whose {@code iss} is not exactly the issuer the consent started with, or that names none where
 * the authorization server announced it, is refused before any code reaches a token endpoint, and
 * its state is used up; the right {@code iss}, or none where nothing was announced, completes.
 */
@OpaaIntegrationTest
class McpServerIssuerIntegrationTest {

  private static final String ADMIN = "/api/v1/admin/mcp-servers";
  private static final String COMPLETE = "/api/v1/connections/authorizations/complete";
  private static final String BASE = "https://opaa.example.org";
  private static final String CLIENT_ID = "opaa-mcp-2266";
  private static final String MISMATCH = ConnectionAuthorizationService.ISSUER_MISMATCH;

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ConnectionAuthorizationRepository authorizationRepository;
  @Autowired private ConnectionProfileRepository profileRepository;
  @Autowired private ProfileRegistrations registrations;
  @Autowired private ConnectedAccountService accounts;
  @Autowired private SourceConsentService consents;
  @Autowired private EffectiveSourceSettings effective;
  @Autowired private ObjectProvider<SourceConnectorRegistry> connectors;
  @Autowired private CredentialsEncryptor encryptor;
  @Autowired private TargetAddressValidator targetAddressValidator;
  @Autowired private PlatformTransactionManager transactionManager;

  private final FakeMcpServer serverA = new FakeMcpServer();
  private final FakeMcpServer serverB = new FakeMcpServer();
  private final String suffix = UUID.randomUUID().toString().substring(0, 8);
  private final List<UUID> profiles = new ArrayList<>();
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private Logger root;
  private UUID group;
  private CurrentUser adminCaller;
  private ConnectionAuthorizationService service;

  @BeforeEach
  void aGroupAndTheAdministration() throws Exception {
    appender.list = new CopyOnWriteArrayList<>();
    appender.start();
    root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    root.addAppender(appender);
    mockMvc.perform(as("dev-admin", get("/api/v1/spaces"))).andExpect(status().isOk());
    UUID admin =
        jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, "admin@opaa.local");
    adminCaller =
        CurrentUser.of(admin, Organization.DEFAULT_ID, SystemRole.SYSTEM_ADMIN, "Dev Admin");
    group = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO groups (id, organization_id, kind, name) VALUES (?, ?, 'AD_HOC', ?)",
        group,
        Organization.DEFAULT_ID,
        "MCP-Verantwortliche " + group);
    service =
        new ConnectionAuthorizationService(
            authorizationRepository,
            profileRepository,
            registrations,
            accounts,
            consents,
            effective,
            connectors,
            new PublicBaseUrl(new PublicBaseUrlProperties(BASE)),
            encryptor,
            targetAddressValidator,
            transactionManager,
            Clock.systemUTC());
  }

  @AfterEach
  void removeOwnRows() {
    root.detachAppender(appender);
    serverA.close();
    serverB.close();
    for (UUID profile : profiles) {
      jdbc.update("DELETE FROM connection_authorizations WHERE profile_id = ?", profile);
      jdbc.update("DELETE FROM connection_tokens WHERE profile_id = ?", profile);
      jdbc.update("DELETE FROM connected_accounts WHERE profile_id = ?", profile);
      jdbc.update("DELETE FROM connection_log WHERE profile_id = ?", profile);
      jdbc.update("DELETE FROM notifications WHERE object_id = ?", profile);
      jdbc.update("DELETE FROM audit_log WHERE object_id = ?", profile.toString());
      jdbc.update("DELETE FROM connection_profiles WHERE id = ?", profile);
    }
    jdbc.update("DELETE FROM groups WHERE id = ?", group);
  }

  /**
   * Regression guard for #2266: server A sends the person on to the honest server B, whose code
   * comes back with A's state and B's issuer. It never reaches A's token endpoint.
   */
  @Test
  void aCodeFromAnotherAuthorizationServerNeverReachesTheTokenEndpoint() throws Exception {
    UUID profile = createServer("MCP-Server A", serverA);
    ConnectionAuthorizationService.Started started = start(profile);
    String state = query(started.authorizationUrl()).get("state");
    String codeOfB = serverB.approve(forwardedTo(serverB, started.authorizationUrl()));

    ResultActions refused = complete(state, codeOfB, serverB.issuer());

    assertThat(serverA.requests("authorization_code"))
        .as("the code of server B at the token endpoint of server A")
        .isEmpty();
    assertThat(serverB.requests("authorization_code")).isEmpty();
    refused
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(MISMATCH))
        .andExpect(jsonPath("$.error").value(containsString("nichts verbunden")));
    complete(state, codeOfB, serverA.issuer()).andExpect(status().isNotFound());
    assertThat(serverA.requests("authorization_code")).isEmpty();
    String lines = logLines();
    assertThat(lines).contains("expected issuer").doesNotContain(codeOfB).doesNotContain(state);
  }

  @Test
  void anAnnouncedIssuerMustBeNamed() throws Exception {
    serverA.announceIssuerParameter(true);
    UUID profile = createServer("MCP-Server A", serverA);
    ConnectionAuthorizationService.Started started = start(profile);
    String state = query(started.authorizationUrl()).get("state");
    String code = serverA.approve(started.authorizationUrl());

    complete(state, code, null)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(MISMATCH));

    assertThat(serverA.requests("authorization_code")).isEmpty();
    complete(state, code, serverA.issuer()).andExpect(status().isNotFound());
  }

  @Test
  void anAnnouncedIssuerNamedExactlyCompletes() throws Exception {
    serverA.announceIssuerParameter(true);
    UUID profile = createServer("MCP-Server A", serverA);
    ConnectionAuthorizationService.Started started = start(profile);

    complete(
            query(started.authorizationUrl()).get("state"),
            serverA.approve(started.authorizationUrl()),
            serverA.issuer())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.purpose").value("ACCOUNT"));

    assertThat(serverA.requests("authorization_code")).hasSize(1);
  }

  @Test
  void theIssuerIsComparedWithoutNormalization() throws Exception {
    serverA.announceIssuerParameter(true);
    UUID profile = createServer("MCP-Server A", serverA);
    ConnectionAuthorizationService.Started started = start(profile);

    complete(
            query(started.authorizationUrl()).get("state"),
            serverA.approve(started.authorizationUrl()),
            serverA.issuer() + "/")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(MISMATCH));

    assertThat(serverA.requests("authorization_code")).isEmpty();
  }

  @Test
  void withoutAnnouncementAResponseWithoutIssuerCompletes() throws Exception {
    serverA.announceIssuerParameter(false);
    UUID profile = createServer("MCP-Server A", serverA);
    ConnectionAuthorizationService.Started started = start(profile);

    complete(
            query(started.authorizationUrl()).get("state"),
            serverA.approve(started.authorizationUrl()),
            null)
        .andExpect(status().isOk());

    assertThat(serverA.requests("authorization_code")).hasSize(1);
  }

  @Test
  void anErrorFromAnotherAuthorizationServerIsRefusedAsSuch() throws Exception {
    UUID profile = createServer("MCP-Server A", serverA);
    String state = query(start(profile).authorizationUrl()).get("state");

    mockMvc
        .perform(
            as("dev-admin", post(COMPLETE))
                .content(
                    "{\"state\": \"%s\", \"error\": \"access_denied\", \"iss\": \"%s\"}"
                        .formatted(state, serverB.issuer())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(MISMATCH));
  }

  /** The issuer the consent started with counts, not the one the profile names by now. */
  @Test
  void theIssuerOfTheStartCountsAfterTheProfileMoved() throws Exception {
    UUID profile = createServer("MCP-Server A", serverA);
    ConnectionAuthorizationService.Started started = start(profile);
    String state = query(started.authorizationUrl()).get("state");
    String codeOfB = serverB.approve(forwardedTo(serverB, started.authorizationUrl()));
    serverA.useAuthorizationServerOf(serverB);
    mockMvc
        .perform(
            as("dev-admin", put(ADMIN + "/" + profile))
                .content(request("MCP-Server A", serverA, true)))
        .andExpect(status().isOk());
    assertThat(profileRepository.findById(profile).orElseThrow().getIssuer())
        .isEqualTo(serverB.issuer());

    complete(state, codeOfB, serverB.issuer())
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(MISMATCH));

    assertThat(serverA.requests("authorization_code")).isEmpty();
    assertThat(serverB.requests("authorization_code")).isEmpty();
  }

  private ConnectionAuthorizationService.Started start(UUID profile) {
    return service.start(adminCaller, profile, ConnectionAuthorizationPurpose.ACCOUNT);
  }

  private UUID createServer(String name, FakeMcpServer server) throws Exception {
    String created =
        mockMvc
            .perform(as("dev-admin", post(ADMIN)).content(request(name, server, false)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    UUID id = UUID.fromString(JsonPath.read(created, "$.id"));
    profiles.add(id);
    return id;
  }

  private String request(String name, FakeMcpServer server, boolean confirm) {
    return """
        {"name": "%s %s", "serverUrl": "%s", "clientId": "%s", "clientSecret": "",
         "responsibleGroupId": "%s", "confirmDiscard": %s}
        """
        .formatted(name, suffix, server.resource(), CLIENT_ID, group, confirm);
  }

  private ResultActions complete(String state, String code, String iss) throws Exception {
    return mockMvc.perform(
        as("dev-admin", post(COMPLETE))
            .content(
                "{\"state\": \"%s\", \"code\": \"%s\"%s}"
                    .formatted(state, code, iss == null ? "" : ", \"iss\": \"" + iss + "\"")));
  }

  /** The authorization request of {@code url} as a mixing-up server forwards it to {@code to}. */
  private static URI forwardedTo(FakeMcpServer to, URI url) {
    Map<String, String> query = query(url);
    query.put("resource", to.resource());
    return URI.create(
        to.authorizationEndpoint()
            + "?"
            + query.entrySet().stream()
                .map(
                    entry ->
                        entry.getKey()
                            + "="
                            + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&")));
  }

  private String logLines() {
    return appender.list.stream()
        .map(ILoggingEvent::getFormattedMessage)
        .collect(Collectors.joining("\n"));
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

  private static MockHttpServletRequestBuilder as(
      String user, MockHttpServletRequestBuilder request) {
    return request
        .header(DevAuthFilter.DEV_USER_HEADER, user)
        .contentType(MediaType.APPLICATION_JSON);
  }
}
