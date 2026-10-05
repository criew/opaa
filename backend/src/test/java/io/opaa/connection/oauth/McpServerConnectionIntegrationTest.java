package io.opaa.connection.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import io.opaa.auth.DevAuthFilter;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.PublicBaseUrl;
import io.opaa.common.PublicBaseUrlProperties;
import io.opaa.connection.account.ConnectedAccountService;
import io.opaa.connection.consent.SourceConsentService;
import io.opaa.connection.profile.ConnectionProfileRepository;
import io.opaa.connection.profile.EffectiveSourceSettings;
import io.opaa.connection.profile.McpServerTokens;
import io.opaa.connection.profile.ProfileRegistrations;
import io.opaa.connection.token.ConnectionSecrets;
import io.opaa.connection.token.SecretOwner.PersonOwned;
import io.opaa.connection.token.SecretRefusedException;
import io.opaa.indexing.source.SourceBlock.Reason;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.organization.Organization;
import io.opaa.security.CredentialsEncryptor;
import io.opaa.security.TargetAddressValidator;
import io.opaa.test.FakeMcpServer;
import io.opaa.test.OpaaIntegrationTest;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * The MCP foundation (#2173) against two fake MCP servers with their own authorization servers:
 * discovery through the Protected Resource Metadata, the consent with the resource indicator in the
 * authorization and the token request and in every renewal, a token of server A never handed out
 * for server B, revocation on every end, the connector paths blind to MCP servers, and no token,
 * code, state or secret in an answer, a log line or a protocol.
 */
@OpaaIntegrationTest
class McpServerConnectionIntegrationTest {

  private static final String ADMIN = "/api/v1/admin/mcp-servers";
  private static final String BASE = "https://opaa.example.org";
  private static final String CLIENT_ID = "opaa-mcp-2173";
  private static final String CLIENT_SECRET = "mcp-client-secret-2173-geheim";

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
  @Autowired private ConnectionSecrets secrets;
  @Autowired private McpServerTokens mcpTokens;

  private final FakeMcpServer serverA = new FakeMcpServer();
  private final FakeMcpServer serverB = new FakeMcpServer();
  private final String suffix = UUID.randomUUID().toString().substring(0, 8);
  private final List<UUID> profiles = new ArrayList<>();
  private final List<String> answers = new ArrayList<>();
  private final List<String> sensitive = new ArrayList<>();
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private Logger root;
  private UUID group;
  private UUID admin;
  private CurrentUser adminCaller;
  private ConnectionAuthorizationService service;

  @BeforeEach
  void aGroupAndTheAdministration() throws Exception {
    appender.list = new CopyOnWriteArrayList<>();
    appender.start();
    root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    root.addAppender(appender);
    ((Logger) LoggerFactory.getLogger("io.opaa")).setLevel(Level.DEBUG);
    sensitive.add(CLIENT_SECRET);
    call("dev-user", get("/api/v1/spaces"), null);
    call("dev-admin", get("/api/v1/spaces"), null);
    admin = userIdOf("admin@opaa.local");
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
    ((Logger) LoggerFactory.getLogger("io.opaa")).setLevel(null);
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

  /** Acceptance criterion 1 of #2173: discovery, consent, resource in both requests. */
  @Test
  void discoveryConsentAndRenewalNameTheServerAsResource() throws Exception {
    String created = createServer("MCP-Server A", serverA, CLIENT_SECRET);
    UUID profile = idOf(created);

    assertThat((String) JsonPath.read(created, "$.serverUrl")).isEqualTo(serverA.resource());
    assertThat((String) JsonPath.read(created, "$.issuer")).isEqualTo(serverA.issuer());
    assertThat((String) JsonPath.read(created, "$.tokenEndpoint"))
        .isEqualTo(serverA.tokenEndpoint());
    assertThat((String) JsonPath.read(created, "$.scopes")).isEqualTo("tools.read offline_access");
    assertThat((Boolean) JsonPath.read(created, "$.clientSecretSet")).isTrue();
    assertThat((String) JsonPath.read(created, "$.authMethod")).isEqualTo("OAUTH");
    assertThat((String) JsonPath.read(created, "$.authorizationEndpoint"))
        .isEqualTo(serverA.authorizationEndpoint());
    assertThat((String) JsonPath.read(created, "$.revocationEndpoint"))
        .isEqualTo(serverA.revocationEndpoint());
    assertThat((String) JsonPath.read(created, "$.responsibleGroupId")).isEqualTo(group.toString());
    assertThat((Boolean) JsonPath.read(created, "$.locked")).isFalse();
    assertThat((Integer) JsonPath.read(created, "$.connectedAccounts.fewerThan")).isPositive();
    assertThat(created).doesNotContain(CLIENT_SECRET);

    ConnectionAuthorizationService.Started started =
        service.start(adminCaller, profile, ConnectionAuthorizationPurpose.ACCOUNT);
    Map<String, String> query = query(started.authorizationUrl());
    assertThat(started.authorizationUrl().toString())
        .startsWith(serverA.authorizationEndpoint() + "?");
    assertThat(query)
        .containsEntry("resource", serverA.resource())
        .containsEntry("client_id", CLIENT_ID)
        .containsEntry("code_challenge_method", "S256")
        .containsEntry("scope", "tools.read offline_access");

    complete(serverA, started)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.purpose").value("ACCOUNT"))
        .andExpect(jsonPath("$.account").doesNotExist());

    FakeMcpServer.Request exchange = serverA.requests("authorization_code").getFirst();
    assertThat(exchange.form()).containsEntry("resource", serverA.resource());
    assertThat(exchange.authorization()).isEqualTo(basic(CLIENT_ID, CLIENT_SECRET));
    sensitive.add(exchange.form().get("code_verifier"));
    assertThat(
            jdbc.queryForObject(
                "SELECT issued_for FROM connection_tokens WHERE profile_id = ?",
                String.class,
                profile))
        .isEqualTo(serverA.resource());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM connection_log WHERE profile_id = ? AND event_type ="
                    + " 'CONNECTED'",
                Integer.class,
                profile))
        .isOne();

    McpServerTokens.Access access = mcpTokens.accessFor(admin, profile);
    sensitive.add(access.token().value());
    assertThat(access.resource()).isEqualTo(serverA.resource());
    assertThat(serverA.audienceOf(access.token().value())).isEqualTo(serverA.resource());

    jdbc.update(
        "UPDATE connection_tokens SET access_token_expires_at = now() + interval '1 minute',"
            + " updated_at = now() - interval '1 hour' WHERE profile_id = ?",
        profile);
    McpServerTokens.Access renewed = mcpTokens.accessFor(admin, profile);
    sensitive.add(renewed.token().value());

    assertThat(renewed.token().value()).isNotEqualTo(access.token().value());
    assertThat(serverA.requests("refresh_token"))
        .singleElement()
        .satisfies(
            request -> assertThat(request.form()).containsEntry("resource", serverA.resource()));
    assertThat(serverA.audienceOf(renewed.token().value())).isEqualTo(serverA.resource());
    assertNothingLeaked(profile);
  }

  /** Acceptance criterion 2 of #2173: a token of server A is never used for server B. */
  @Test
  void aTokenOfServerANeverReachesServerB() throws Exception {
    UUID profileA = idOf(createServer("MCP-Server A", serverA, null));
    UUID profileB = idOf(createServer("MCP-Server B", serverB, null));
    connect(serverA, profileA);

    assertRefused(() -> mcpTokens.accessFor(admin, profileB), Reason.NOT_CONNECTED);
    assertRefused(
        () -> secrets.current(new PersonOwned(profileA, admin), serverB.resource()),
        Reason.TARGET_OUTSIDE_PROFILE);
    assertThat(serverB.requests()).isEmpty();

    // the same origin, another path: another server
    assertRefused(
        () -> secrets.current(new PersonOwned(profileA, admin), serverA.resource() + "/andere"),
        Reason.TARGET_OUTSIDE_PROFILE);

    // the profile pointed at server B: the token of A is revoked at A and goes
    String held = mcpTokens.accessFor(admin, profileA).token().value();
    call("dev-admin", put(ADMIN + "/" + profileA), request("MCP-Server A", serverB, null, true));

    assertRefused(() -> mcpTokens.accessFor(admin, profileA), Reason.NOT_CONNECTED);
    assertThat(serverA.revocations()).singleElement();
    assertThat(serverB.requests()).isEmpty();
    assertThat(serverA.audienceOf(held)).isEqualTo(serverA.resource());
  }

  /**
   * Regression guard: the client secret registered at one authorization server never reaches the
   * token endpoint of another; a new address needs a new secret, or none for a public client.
   */
  @Test
  void aNewAddressNeedsANewClientSecret() throws Exception {
    UUID profile = idOf(createServer("MCP-Server A", serverA, CLIENT_SECRET));
    connect(serverA, profile);

    mockMvc
        .perform(
            as("dev-admin", put(ADMIN + "/" + profile))
                .content(request("MCP-Server A", serverB, null, true)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MCP_SERVER_CLIENT_SECRET_REQUIRED"))
        .andExpect(
            jsonPath("$.error").value(org.hamcrest.Matchers.containsString(serverA.issuer())))
        .andExpect(
            jsonPath("$.error").value(org.hamcrest.Matchers.containsString(serverB.issuer())));
    assertThat(mcpTokens.accessFor(admin, profile).resource()).isEqualTo(serverA.resource());

    String changed =
        call("dev-admin", put(ADMIN + "/" + profile), request("MCP-Server A", serverB, "", true));
    assertThat((Boolean) JsonPath.read(changed, "$.clientSecretSet")).isFalse();
    connect(serverB, profile);

    FakeMcpServer.Request exchange = serverB.requests("authorization_code").getFirst();
    assertThat(exchange.authorization()).isNull();
    assertThat(exchange.form())
        .containsEntry("client_id", CLIENT_ID)
        .doesNotContainKey("client_secret");
    assertThat(serverB.requests())
        .noneSatisfy(request -> assertThat(request.authorization()).isNotNull());
  }

  /**
   * Regression guard: a server naming another authorization server at the same address keeps no
   * secret either, and the question names the old and the new one.
   */
  @Test
  void aNewAuthorizationServerAtTheSameAddressNeedsANewClientSecret() throws Exception {
    UUID profile = idOf(createServer("MCP-Server A", serverA, CLIENT_SECRET));
    serverA.useAuthorizationServerOf(serverB);

    mockMvc
        .perform(
            as("dev-admin", put(ADMIN + "/" + profile))
                .content(request("MCP-Server A", serverA, null, true)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MCP_SERVER_CLIENT_SECRET_REQUIRED"));
    mockMvc
        .perform(
            as("dev-admin", put(ADMIN + "/" + profile))
                .content(request("MCP-Server A", serverA, "neues-geheimnis-bei-b", false)))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.error").value(org.hamcrest.Matchers.containsString(serverA.issuer())))
        .andExpect(
            jsonPath("$.error").value(org.hamcrest.Matchers.containsString(serverB.issuer())));

    String changed =
        call(
            "dev-admin",
            put(ADMIN + "/" + profile),
            request("MCP-Server A", serverA, "neues-geheimnis-bei-b", true));
    assertThat((String) JsonPath.read(changed, "$.issuer")).isEqualTo(serverB.issuer());
    assertThat((Boolean) JsonPath.read(changed, "$.clientSecretSet")).isTrue();
  }

  @Test
  void aChangeOfTheServerIsConfirmedFirst() throws Exception {
    UUID profile = idOf(createServer("MCP-Server A", serverA, null));
    connect(serverA, profile);

    mockMvc
        .perform(
            as("dev-admin", put(ADMIN + "/" + profile))
                .content(request("MCP-Server A", serverB, null, false)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONNECTION_PROFILE_CONFIRMATION_REQUIRED"))
        .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("etwaige")));

    assertThat(mcpTokens.accessFor(admin, profile).resource()).isEqualTo(serverA.resource());
    call(
        "dev-admin", put(ADMIN + "/" + profile), request("MCP-Server A neu", serverA, null, false));
    assertThat(mcpTokens.accessFor(admin, profile).resource()).isEqualTo(serverA.resource());
  }

  @Test
  void disconnectShutdownLockAndDeletionEndTheConnectionAndRevoke() throws Exception {
    UUID profile = idOf(createServer("MCP-Server A", serverA, CLIENT_SECRET));
    connect(serverA, profile);

    mockMvc
        .perform(as("dev-admin", delete("/api/v1/me/connected-accounts/" + profile)))
        .andExpect(status().isNoContent());
    assertThat(serverA.revocations()).hasSize(1);
    assertRefused(() -> mcpTokens.accessFor(admin, profile), Reason.NOT_CONNECTED);

    connect(serverA, profile);
    String shutdown = call("dev-admin", post(ADMIN + "/" + profile + "/disconnect-all"), null);
    assertThat((Integer) JsonPath.read(shutdown, "$.connectedAccounts.fewerThan")).isPositive();
    assertThat(serverA.revocations()).hasSize(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM connection_log WHERE profile_id = ? AND event_type ="
                    + " 'EMERGENCY_DISCONNECTED'",
                Integer.class,
                profile))
        .isOne();
    // the accounts page does not show an MCP connection, so no notice sends anyone there
    assertThat(
            jdbc.queryForList(
                "SELECT body FROM notifications WHERE object_id = ?", String.class, profile))
        .isNotEmpty()
        .noneSatisfy(body -> assertThat(body).contains("Verbundene Konten"));

    connect(serverA, profile);
    call("dev-admin", put(ADMIN + "/" + profile + "/lock"), "{\"locked\": true}");
    assertRefused(() -> mcpTokens.accessFor(admin, profile), Reason.PROFILE_LOCKED);
    assertThatThrownBy(
            () -> service.start(adminCaller, profile, ConnectionAuthorizationPurpose.ACCOUNT))
        .isInstanceOf(AccessDeniedException.class);
    call("dev-admin", put(ADMIN + "/" + profile + "/lock"), "{\"locked\": false}");

    mockMvc
        .perform(as("dev-admin", delete(ADMIN + "/" + profile)))
        .andExpect(status().isNoContent());
    assertThat(serverA.revocations()).hasSize(3);
    assertRefused(() -> mcpTokens.accessFor(admin, profile), Reason.ACCESS_REMOVED);
    assertNothingLeaked(profile);
  }

  @Test
  void onlyTheSystemAdministrationConnectsUntilTheUseIsReleased() throws Exception {
    UUID profile = idOf(createServer("MCP-Server A", serverA, null));
    UUID person = userIdOf("dev-user@opaa.local");
    CurrentUser user = CurrentUser.of(person, Organization.DEFAULT_ID, SystemRole.USER, "Dev User");

    assertThatThrownBy(() -> service.start(user, profile, ConnectionAuthorizationPurpose.ACCOUNT))
        .isInstanceOf(AccessDeniedException.class);
    mockMvc.perform(as("dev-user", get(ADMIN))).andExpect(status().isForbidden());
  }

  @Test
  void theConnectorPathsDoNotSeeAnMcpServer() throws Exception {
    UUID profile = idOf(createServer("MCP-Server A", serverA, null));
    connect(serverA, profile);

    String connectorProfiles = call("dev-admin", get("/api/v1/admin/connection-profiles"), null);
    assertThat(connectorProfiles).doesNotContain(profile.toString());
    mockMvc
        .perform(as("dev-admin", get("/api/v1/admin/connection-profiles/" + profile)))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            as("dev-admin", put("/api/v1/admin/connection-profiles/" + profile + "/lock"))
                .content("{\"locked\": true}"))
        .andExpect(status().isNotFound());
    assertThat(call("dev-admin", get("/api/v1/me/connected-accounts"), null))
        .doesNotContain(profile.toString());
    mockMvc
        .perform(
            as("dev-admin", put("/api/v1/me/connected-accounts/" + profile))
                .content("{\"secret\": \"app-passwort\"}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            as("dev-admin", post("/api/v1/connections/authorizations"))
                .content(
                    "{\"profileId\": \"%s\", \"purpose\": \"LIBRARY_NEW\",".formatted(profile)
                        + " \"serviceAccountConfirmed\": true}"))
        .andExpect(status().isBadRequest());
    assertThat(call("dev-admin", get(ADMIN), null)).contains(profile.toString());
  }

  private String createServer(String name, FakeMcpServer server, String secret) throws Exception {
    String created = call("dev-admin", post(ADMIN), request(name, server, secret, false));
    profiles.add(idOf(created));
    return created;
  }

  private String request(String name, FakeMcpServer server, String secret, boolean confirm) {
    return """
        {"name": "%s %s", "serverUrl": "%s/", "clientId": "%s", %s
         "responsibleGroupId": "%s", "confirmDiscard": %s}
        """
        .formatted(
            name,
            suffix,
            server.resource(),
            CLIENT_ID,
            secret == null ? "" : "\"clientSecret\": \"" + secret + "\",",
            group,
            confirm);
  }

  private void connect(FakeMcpServer server, UUID profile) throws Exception {
    complete(server, service.start(adminCaller, profile, ConnectionAuthorizationPurpose.ACCOUNT))
        .andExpect(status().isOk());
  }

  private ResultActions complete(
      FakeMcpServer server, ConnectionAuthorizationService.Started started) throws Exception {
    URI url = started.authorizationUrl();
    String code = server.approve(url);
    String state = query(url).get("state");
    sensitive.addAll(List.of(code, state));
    ResultActions result =
        mockMvc.perform(
            as("dev-admin", post("/api/v1/connections/authorizations/complete"))
                .content("{\"state\": \"%s\", \"code\": \"%s\"}".formatted(state, code)));
    answers.add(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    return result;
  }

  private static void assertRefused(Runnable handOut, Reason reason) {
    assertThatThrownBy(handOut::run)
        .isInstanceOfSatisfying(
            SecretRefusedException.class, e -> assertThat(e.reason()).isEqualTo(reason));
  }

  /** No token, code, verifier, state or client secret in an answer, a log line or a protocol. */
  private void assertNothingLeaked(UUID profile) {
    sensitive.addAll(
        serverA.requests().stream()
            .flatMap(request -> request.form().values().stream())
            .filter(value -> value.startsWith("mcp"))
            .toList());
    List<String> lines = new ArrayList<>(answers);
    for (ILoggingEvent event : appender.list) {
      lines.add(event.getFormattedMessage());
      if (event.getThrowableProxy() != null) {
        lines.add(ThrowableProxyUtil.asString(event.getThrowableProxy()));
      }
    }
    lines.addAll(
        jdbc.queryForList(
            "SELECT coalesce(before, '') || coalesce(after, '') FROM audit_log"
                + " WHERE object_id = ?",
            String.class,
            profile.toString()));
    lines.addAll(
        jdbc.queryForList(
            "SELECT row_to_json(l)::text FROM connection_log l WHERE profile_id = ?",
            String.class,
            profile));
    for (String value : sensitive) {
      if (value != null) {
        assertThat(lines).noneSatisfy(line -> assertThat(line).contains(value));
      }
    }
  }

  private String call(String user, MockHttpServletRequestBuilder request, String body)
      throws Exception {
    MockHttpServletRequestBuilder built = as(user, request);
    if (body != null) {
      built.content(body);
    }
    String answer =
        mockMvc
            .perform(built)
            .andExpect(result -> assertThat(result.getResponse().getStatus()).isBetween(200, 299))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    answers.add(answer);
    return answer;
  }

  private UUID userIdOf(String email) {
    return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
  }

  private static UUID idOf(String json) {
    return UUID.fromString(JsonPath.read(json, "$.id"));
  }

  private static String basic(String id, String secret) {
    return "Basic "
        + Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
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
