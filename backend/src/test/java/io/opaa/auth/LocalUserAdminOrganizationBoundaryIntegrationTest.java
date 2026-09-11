package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.local.LocalAccountLinks;
import io.opaa.auth.local.LocalActionTokenRepository;
import io.opaa.auth.local.LocalCredentials;
import io.opaa.auth.local.LocalCredentialsRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.OpaaMockMvcTest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The organization boundary of the local account administration, end to end through the real {@code
 * dev} chain against Postgres - the same gap {@link
 * AdminControllerOrganizationBoundaryIntegrationTest} closes for the role endpoint: a local account
 * of another organization is a 404 on every operation, never a 403, and a created account lands in
 * the acting administrator's organization. Without SMTP and without a public base URL the
 * invitation falls back to the displayed link, here relative to the installation.
 */
@OpaaMockMvcTest
class LocalUserAdminOrganizationBoundaryIntegrationTest {

  private static final String LOCAL_USERS = "/api/v1/admin/local-users";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private LocalCredentialsRepository credentials;
  @Autowired private LocalActionTokenRepository actionTokens;
  @Autowired private OrganizationRepository organizations;
  @Autowired private JdbcTemplate jdbc;

  private UUID otherOrganizationId;
  private final List<UUID> createdUsers = new java.util.ArrayList<>();

  @AfterEach
  void tearDown() {
    actionTokens.deleteAll();
    for (UUID id : createdUsers) {
      jdbc.update("DELETE FROM spaces WHERE owner_id = ?", id);
      credentials.deleteById(id);
      users.deleteById(id);
    }
    jdbc.update("DELETE FROM audit_log WHERE event_type LIKE 'LOCAL_USER_%'");
    if (otherOrganizationId != null) {
      jdbc.update("DELETE FROM audit_log WHERE organization_id = ?", otherOrganizationId);
      organizations.deleteById(otherOrganizationId);
      otherOrganizationId = null;
    }
  }

  private RequestPostProcessor devAdmin() {
    return request -> {
      request.addHeader(DevAuthFilter.DEV_USER_HEADER, "dev-admin");
      return request;
    };
  }

  private UUID localUserInOtherOrganization() {
    otherOrganizationId =
        organizations.save(new Organization(UUID.randomUUID(), "Other Org")).getId();
    User user = User.localAccount("fremd-" + UUID.randomUUID() + "@stadt.example", "Fremd");
    user.setOrganizationId(otherOrganizationId);
    UUID id = users.save(user).getId();
    Instant now = Instant.now();
    LocalCredentials row = new LocalCredentials(id, "Testkonto", now);
    row.markEmailVerified(now);
    credentials.save(row);
    createdUsers.add(id);
    return id;
  }

  @Test
  void aLocalAccountOfAnotherOrganizationIsInvisibleOnEveryOperation() throws Exception {
    mockMvc.perform(get(LOCAL_USERS).with(devAdmin())).andExpect(status().isOk());
    UUID foreign = localUserInOtherOrganization();

    mockMvc
        .perform(get(LOCAL_USERS + "/" + foreign).with(devAdmin()))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(post(LOCAL_USERS + "/" + foreign + "/lock").with(devAdmin()))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(post(LOCAL_USERS + "/" + foreign + "/unlock").with(devAdmin()))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(post(LOCAL_USERS + "/" + foreign + "/password-reset").with(devAdmin()))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(post(LOCAL_USERS + "/" + foreign + "/password").with(devAdmin()))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            patch(LOCAL_USERS + "/" + foreign)
                .with(devAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Neu\"}"))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(delete(LOCAL_USERS + "/" + foreign).with(devAdmin()))
        .andExpect(status().isNotFound());
    MvcResult list = mockMvc.perform(get(LOCAL_USERS).with(devAdmin())).andReturn();
    List<String> ids = JsonPath.read(list.getResponse().getContentAsString(), "$.items[*].id");
    assertThat(ids).doesNotContain(foreign.toString());
    assertThat(users.findById(foreign)).isPresent();
    assertThat(credentials.findById(foreign).orElseThrow().getLockedAt()).isNull();
  }

  @Test
  void aCreatedAccountBelongsToTheActingAdministratorsOrganizationAndShowsTheRelativeLink()
      throws Exception {
    mockMvc.perform(get(LOCAL_USERS).with(devAdmin())).andExpect(status().isOk());
    String email = "neu-" + UUID.randomUUID() + "@stadt.example";
    MvcResult created =
        mockMvc
            .perform(
                post(LOCAL_USERS)
                    .with(devAdmin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"email\":\""
                            + email
                            + "\",\"displayName\":\"Neu\",\"mode\":\"INVITE\","
                            + "\"createdReason\":\"Test\",\"noExpiry\":true}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.emailSent").value(false))
            .andExpect(jsonPath("$.deliveryPath").value("LINK_DISPLAYED"))
            .andExpect(jsonPath("$.user.expiresAt").doesNotExist())
            .andReturn();
    String body = created.getResponse().getContentAsString();
    UUID id = UUID.fromString(JsonPath.read(body, "$.user.id"));
    createdUsers.add(id);
    assertThat(JsonPath.<String>read(body, "$.setupUrl"))
        .startsWith(
            "/" + LocalAccountLinks.SET_PASSWORD_PATH + "?" + LocalAccountLinks.TOKEN_QUERY + "=");
    User user = users.findById(id).orElseThrow();
    assertThat(user.getOrganizationId()).isEqualTo(Organization.DEFAULT_ID);
    assertThat(user.getIssuer()).isEqualTo(LocalIssuer.URN);
    assertThat(user.getSubject()).isEqualTo(id.toString());
    assertThat(user.getSystemRole()).isEqualTo(SystemRole.USER);
    assertThat(user.getLastLoginAt()).isNull();
    mockMvc
        .perform(get(LOCAL_USERS + "/" + id).with(devAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.activity").value("NEVER"));
  }
}
