package io.opaa.connection.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zaxxer.hikari.HikariDataSource;
import io.opaa.auth.DevAuthFilter;
import io.opaa.organization.OrganizationRepository;
import io.opaa.outbound.OutboundProxy;
import io.opaa.outbound.OutboundProxySelector;
import io.opaa.outbound.ProxyAddress;
import io.opaa.test.OpaaIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import javax.sql.DataSource;
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
 * The house-wide outbound proxy through the API against the Liquibase schema: read and replace it
 * as system administration only, a German field error per invalid field, the governance event with
 * both states, and the route of the JVM changing only once the change committed.
 */
@OpaaIntegrationTest
class OutboundProxyIntegrationTest {

  private static final String PATH = "/api/v1/admin/outbound-proxy";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private OutboundProxy outboundProxy;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private DataSource dataSource;

  private final OutboundProxySelector selector = OutboundProxySelector.instance();

  @BeforeEach
  void provisionCallers() throws Exception {
    mockMvc.perform(get("/api/v1/spaces").with(devUser())).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/spaces").with(devAdmin())).andExpect(status().isOk());
    removeOwnEvents();
  }

  /** The settings row itself is put back by {@code SeededRowRestorer}, the route with it. */
  @AfterEach
  void removeOwnEvents() {
    jdbcTemplate.update("DELETE FROM audit_log WHERE event_type = 'OUTBOUND_PROXY_CHANGED'");
  }

  @Test
  void withoutASettingEveryConnectionGoesDirect() throws Exception {
    mockMvc
        .perform(get(PATH).with(devAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.proxyUrl").doesNotExist())
        .andExpect(jsonPath("$.noProxy").isEmpty());
    assertThat(selector.isProxied("api.example.com")).isFalse();
  }

  @Test
  void theSystemAdministrationSetsTheProxyAndTheRouteFollows() throws Exception {
    mockMvc
        .perform(
            put(PATH)
                .with(devAdmin())
                .content(
                    "{\"proxyUrl\":\"Proxy.Example.org:3128\","
                        + "\"noProxy\":[\".Intranet.Example\",\"10.0.0.0/8\",\" \"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.proxyUrl").value("http://proxy.example.org:3128"))
        .andExpect(jsonPath("$.noProxy[0]").value("intranet.example"))
        .andExpect(jsonPath("$.noProxy[1]").value("10.0.0.0/8"))
        .andExpect(jsonPath("$.noProxy.length()").value(2))
        .andExpect(jsonPath("$.updatedAt").isNotEmpty());

    mockMvc
        .perform(get(PATH).with(devAdmin()))
        .andExpect(jsonPath("$.proxyUrl").value("http://proxy.example.org:3128"));
    assertThat(selector.isProxied("api.example.com")).isTrue();
    assertThat(selector.isProxied("wiki.intranet.example")).isFalse();
    assertThat(selector.isProxied("10.1.2.3")).isFalse();

    mockMvc
        .perform(put(PATH).with(devAdmin()).content("{\"proxyUrl\":null,\"noProxy\":[]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.proxyUrl").doesNotExist());
    assertThat(selector.isProxied("api.example.com")).isFalse();
  }

  @Test
  void everyChangeIsAGovernanceEventWithBothStates() throws Exception {
    mockMvc
        .perform(
            put(PATH)
                .with(devAdmin())
                .content("{\"proxyUrl\":\"http://proxy.example.org:3128\",\"noProxy\":[]}"))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            put(PATH)
                .with(devAdmin())
                .content("{\"proxyUrl\":\"\",\"noProxy\":[\"intranet.example\"]}"))
        .andExpect(status().isOk());

    List<Map<String, Object>> events =
        jdbcTemplate.queryForList(
            "SELECT before, after, object_type, object_label FROM audit_log"
                + " WHERE event_type = 'OUTBOUND_PROXY_CHANGED' ORDER BY recorded_at");
    assertThat(events).hasSize(2);
    assertThat(events.get(0).get("object_type")).isEqualTo("SYSTEM_SETTING");
    assertThat(events.get(0).get("object_label")).isEqualTo("Ausgangsproxy");
    assertThat((String) events.get(0).get("before")).contains("\"proxyUrl\":\"\"");
    assertThat((String) events.get(0).get("after"))
        .contains("\"proxyUrl\":\"http://proxy.example.org:3128\"");
    assertThat((String) events.get(1).get("before"))
        .contains("\"proxyUrl\":\"http://proxy.example.org:3128\"");
    assertThat((String) events.get(1).get("after"))
        .contains("\"proxyUrl\":\"\"")
        .contains("intranet.example");
  }

  @Test
  void onlyTheSystemAdministrationReadsAndChangesTheProxy() throws Exception {
    mockMvc.perform(get(PATH).with(devUser())).andExpect(status().isForbidden());
    mockMvc
        .perform(
            put(PATH)
                .with(devUser())
                .content("{\"proxyUrl\":\"proxy.example.org:3128\",\"noProxy\":[]}"))
        .andExpect(status().isForbidden());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbound_proxy_settings", Integer.class))
        .isZero();
  }

  @Test
  void anInvalidProxyAddressIsAFieldError() throws Exception {
    mockMvc
        .perform(
            put(PATH)
                .with(devAdmin())
                .content(
                    "{\"proxyUrl\":\"https://user:pw@proxy.example.org:3128\",\"noProxy\":[]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("proxyUrl"))
        .andExpect(jsonPath("$.fieldErrors[0].code").value("INVALID"));
    assertNothingStored();
  }

  @Test
  void anInvalidExclusionIsAFieldErrorNamingTheEntry() throws Exception {
    mockMvc
        .perform(
            put(PATH)
                .with(devAdmin())
                .content(
                    "{\"proxyUrl\":\"proxy.example.org:3128\","
                        + "\"noProxy\":[\"*\",\"intranet.example:8080\"]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("noProxy"))
        .andExpect(jsonPath("$.fieldErrors[0].code").value("INVALID"))
        .andExpect(
            jsonPath("$.fieldErrors[0].message").value(containsString("intranet.example:8080")));
    assertNothingStored();
  }

  @Test
  void tooManyExclusionsAreAFieldError() throws Exception {
    String entries =
        String.join(
            ",", IntStream.range(0, 101).mapToObj(i -> "\"host" + i + ".example\"").toList());
    mockMvc
        .perform(
            put(PATH).with(devAdmin()).content("{\"proxyUrl\":null,\"noProxy\":[" + entries + "]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("noProxy"))
        .andExpect(jsonPath("$.fieldErrors[0].code").value("TOO_MANY"));
    assertNothingStored();
  }

  @Test
  void bothFieldsInvalidNameBothFields() throws Exception {
    mockMvc
        .perform(
            put(PATH)
                .with(devAdmin())
                .content("{\"proxyUrl\":\"socks5://proxy:1080\",\"noProxy\":[\"a b\"]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("proxyUrl"))
        .andExpect(jsonPath("$.fieldErrors[1].field").value("noProxy"));
  }

  /** The selector is JVM-wide: a change that rolls back must never have routed a connection. */
  @Test
  void theRouteChangesOnlyAfterTheCommit() {
    ProxyAddress proxy = new ProxyAddress("proxy.example.org", 3128);

    transactionTemplate.executeWithoutResult(
        status -> {
          outboundProxy.change(proxy, List.of());
          assertThat(selector.isProxied("api.example.com")).isFalse();
          status.setRollbackOnly();
        });
    assertThat(selector.isProxied("api.example.com")).isFalse();

    transactionTemplate.executeWithoutResult(
        status -> {
          outboundProxy.change(proxy, List.of());
          assertThat(selector.isProxied("api.example.com")).isFalse();
        });
    assertThat(selector.isProxied("api.example.com")).isTrue();
  }

  /** JDBC opens plain sockets, which never take the HTTP proxy - even with a proxy set. */
  @Test
  void theDatabaseStaysReachableWithAProxySet() throws Exception {
    transactionTemplate.executeWithoutResult(
        status -> outboundProxy.change(new ProxyAddress("proxy.example.invalid", 3128), List.of()));
    assertThat(selector.isProxied("db.example.com")).isTrue();

    assertThat(organizationRepository.count()).isPositive();
    HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
    try (Connection fresh =
            DriverManager.getConnection(pool.getJdbcUrl(), pool.getUsername(), pool.getPassword());
        ResultSet result = fresh.createStatement().executeQuery("SELECT 1")) {
      assertThat(result.next()).isTrue();
    }
  }

  private void assertNothingStored() {
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbound_proxy_settings", Integer.class))
        .isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log WHERE event_type = 'OUTBOUND_PROXY_CHANGED'",
                Integer.class))
        .isZero();
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
