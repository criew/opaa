package io.opaa.connection.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.DevAuthFilter;
import io.opaa.indexing.source.profileprobe.ProfileProbeSourceConnector;
import io.opaa.organization.Organization;
import io.opaa.test.ConnectorReleases;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnOrganizationFixtures;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
 * Connection profile requests ("Zugangswunsch") through the API: submitting with duplicate, hourly
 * budget and ceiling, the address rule, who sees what, resolving with and without a new profile,
 * the notifications both ways, and that a request goes with the requesting account.
 */
@OpaaIntegrationTest
class ConnectionProfileRequestIntegrationTest {

  private static final String SUBMIT = "/api/v1/connection-profile-requests";
  private static final String MINE = "/api/v1/me/connection-profile-requests";
  private static final String ADMIN = "/api/v1/admin/connection-profile-requests";
  private static final String PROFILES = "/api/v1/admin/connection-profiles";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OwnOrganizationFixtures organizationFixtures;
  @Autowired private ConnectionProfileRequestService requests;

  private final List<UUID> profiles = new ArrayList<>();
  private final List<UUID> organizations = new ArrayList<>();
  private final List<UUID> ownUsers = new ArrayList<>();
  private UUID devUser;
  private UUID devAdmin;

  @BeforeEach
  void setUp() throws Exception {
    mockMvc.perform(as("dev-user", get(MINE))).andExpect(status().isOk());
    mockMvc.perform(as("dev-admin", get(MINE))).andExpect(status().isOk());
    devUser = userId("dev-user");
    devAdmin = userId("dev-admin");
    removeRequestsOf(devUser, devAdmin);
  }

  @AfterEach
  void tearDown() {
    removeRequestsOf(devUser, devAdmin);
    removeRequestsOf(ownUsers.toArray(UUID[]::new));
    for (UUID user : ownUsers) {
      jdbc.update("DELETE FROM users WHERE id = ?", user);
    }
    for (UUID profile : profiles) {
      ConnectorReleases.withdraw(jdbc, "PROFILE:" + profile);
      jdbc.update("DELETE FROM connection_profiles WHERE id = ?", profile);
    }
    organizationFixtures.removeOrganizations(organizations.toArray(UUID[]::new));
  }

  @Test
  void aRequestReachesTheAdministrationAndItsRequesterWithoutTheReasonInTheNotification()
      throws Exception {
    UUID request =
        submit("dev-user", "PROFILE_PROBE", "HTTPS://Cloud.Example.org/", "Für das Projektteam");

    mockMvc
        .perform(as("dev-admin", get(ADMIN + "?state=OPEN&size=100")))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.items[?(@.id == '" + request + "')].serverUrl")
                .value(Matchers.hasItem("https://cloud.example.org")))
        .andExpect(
            jsonPath("$.items[?(@.id == '" + request + "')].reason")
                .value(Matchers.hasItem("Für das Projektteam")))
        .andExpect(
            jsonPath("$.items[?(@.id == '" + request + "')].requestedByName")
                .value(Matchers.hasItem("Dev User")));
    mockMvc
        .perform(as("dev-user", get(MINE)))
        .andExpect(jsonPath("$[0].id").value(request.toString()))
        .andExpect(jsonPath("$[0].state").value("OPEN"));

    List<String> bodies = notificationBodies(devAdmin, request, "CONNECTION_PROFILE_REQUESTED");
    assertThat(bodies).hasSize(1);
    assertThat(bodies.get(0))
        .contains("https://cloud.example.org", "Testquelle mit Zugang", "Dev User")
        .doesNotContain("Projektteam");
  }

  @Test
  void theSameOpenRequestIsAnsweredWithTheExistingOneInsteadOfASecond() throws Exception {
    UUID first = submit("dev-user", "PROFILE_PROBE", "https://dup.example.org", null);

    mockMvc
        .perform(
            as("dev-user", post(SUBMIT))
                .content(body("PROFILE_PROBE", "https://DUP.example.org/", "x")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(first.toString()));

    assertThat(requestCount(devUser)).isEqualTo(1);
  }

  @Test
  void theHourlyBudgetRefusesTheSixthRequestWith429() throws Exception {
    for (int i = 0; i < 5; i++) {
      submit("dev-user", "PROFILE_PROBE", "https://budget" + i + ".example.org", null);
    }

    mockMvc
        .perform(
            as("dev-user", post(SUBMIT))
                .content(body("PROFILE_PROBE", "https://budget5.example.org", null)))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"))
        .andExpect(jsonPath("$.error").value(Matchers.containsString("letzten Stunde")));
    assertThat(requestCount(devUser)).isEqualTo(5);
  }

  @Test
  void theCeilingOfOpenRequestsRefusesAnotherWith409() throws Exception {
    for (int i = 0; i < 10; i++) {
      jdbc.update(
          "INSERT INTO connection_profile_requests (id, organization_id, source_type, server_url,"
              + " requested_by, created_at, state, version) VALUES (?, ?, 'PROFILE_PROBE', ?, ?,"
              + " now() - interval '2 hours', 'OPEN', 0)",
          UUID.randomUUID(),
          Organization.DEFAULT_ID,
          "https://old" + i + ".example.org",
          devUser);
    }

    mockMvc
        .perform(
            as("dev-user", post(SUBMIT))
                .content(body("PROFILE_PROBE", "https://new.example.org", null)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(ConnectionProfileRequestService.OPEN_LIMIT));
  }

  @Test
  void anAddressOutsideTheTypesRuleAndATypeWithoutProfilesAreRefused() throws Exception {
    mockMvc
        .perform(
            as("dev-user", post(SUBMIT))
                .content(body("PROFILE_PROBE", "ftp://files.example.org", null)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(Matchers.containsString("https://")));
    mockMvc
        .perform(
            as("dev-user", post(SUBMIT))
                .content(body("RSS_FEED", "https://feeds.example.org", null)))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            as("dev-user", post(SUBMIT))
                .content(body("PROFILE_PROBE", "https://long.example.org", "x".repeat(501))))
        .andExpect(status().isBadRequest());

    assertThat(requestCount(devUser)).isZero();
  }

  @Test
  void onlyTheSystemAdministrationListsAndResolvesAndEachPersonSeesOnlyTheirOwn() throws Exception {
    UUID admins = submit("dev-admin", "PROFILE_PROBE", "https://admin.example.org", null);
    UUID users = submit("dev-user", "PROFILE_PROBE", "https://user.example.org", null);

    mockMvc.perform(as("dev-user", get(ADMIN))).andExpect(status().isForbidden());
    mockMvc
        .perform(as("dev-user", put(ADMIN + "/" + users)).content("{\"state\": \"DECLINED\"}"))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(as("dev-user", get(MINE)))
        .andExpect(jsonPath("$[*].id").value(Matchers.hasItem(users.toString())))
        .andExpect(jsonPath("$[*].id").value(Matchers.not(Matchers.hasItem(admins.toString()))));
  }

  @Test
  void aRequestOfAnotherOrganizationStaysOutOfTheListAndCannotBeResolved() throws Exception {
    UUID foreignOrganization = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO organizations (id, name) VALUES (?, ?)",
        foreignOrganization,
        "Fremd " + foreignOrganization);
    organizations.add(foreignOrganization);
    UUID foreignUser = insertUser(foreignOrganization);
    UUID foreignRequest =
        requests
            .submit(
                CurrentUser.of(foreignUser, foreignOrganization, SystemRole.USER, "Fremd"),
                ProfileProbeSourceConnector.TYPE,
                "https://foreign.example.org",
                null)
            .view()
            .request()
            .getId();

    mockMvc
        .perform(as("dev-admin", get(ADMIN + "?size=100")))
        .andExpect(
            jsonPath("$.items[*].id")
                .value(Matchers.not(Matchers.hasItem(foreignRequest.toString()))));
    mockMvc
        .perform(
            as("dev-admin", put(ADMIN + "/" + foreignRequest)).content("{\"state\": \"DECLINED\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void creatingAProfileForARequestResolvesItInTheSameStepAndTellsTheRequester() throws Exception {
    UUID request = submit("dev-user", "PROFILE_PROBE", "https://wish.example.org", null);
    String name = "Zugang Wunsch " + UUID.randomUUID();

    String created =
        mockMvc
            .perform(
                as("dev-admin", post(PROFILES))
                    .content(
                        """
                        {"name": "%s", "sourceType": "PROFILE_PROBE",
                         "serverUrl": "https://wish.example.org", "authMethod": "NONE",
                         "ownership": "LIBRARY", "fulfillsRequestId": "%s"}
                        """
                            .formatted(name, request)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    UUID profile = UUID.fromString(JsonPath.read(created, "$.id"));
    profiles.add(profile);

    mockMvc
        .perform(as("dev-user", get(MINE)))
        .andExpect(jsonPath("$[0].state").value("DONE"))
        .andExpect(jsonPath("$[0].profile.id").value(profile.toString()))
        .andExpect(jsonPath("$[0].profile.name").value(name));
    assertThat(notificationBodies(devUser, request, "CONNECTION_PROFILE_REQUEST_RESOLVED"))
        .singleElement()
        .asString()
        .contains("erledigt", name);
    assertThat(
            jdbc.queryForList(
                "SELECT event_type FROM audit_log WHERE object_id = ?",
                String.class,
                request.toString()))
        .containsExactly("CONNECTION_PROFILE_REQUEST_RESOLVED");

    mockMvc
        .perform(as("dev-admin", put(ADMIN + "/" + request)).content("{\"state\": \"DECLINED\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(ConnectionProfileRequestService.NOT_OPEN));
  }

  @Test
  void aCreationThatFailsLeavesTheRequestOpen() throws Exception {
    UUID request = submit("dev-user", "PROFILE_PROBE", "https://stays.example.org", null);

    mockMvc
        .perform(
            as("dev-admin", post(PROFILES))
                .content(
                    """
                    {"name": "Zugang kaputt", "sourceType": "PROFILE_PROBE",
                     "serverUrl": "ftp://stays.example.org", "authMethod": "NONE",
                     "ownership": "LIBRARY", "fulfillsRequestId": "%s"}
                    """
                        .formatted(request)))
        .andExpect(status().isBadRequest());

    assertThat(stateOf(request)).isEqualTo("OPEN");
  }

  @Test
  void aDeclineCarriesTheAnswerAndAProfileOfAnotherTypeIsRefused() throws Exception {
    UUID request = submit("dev-user", "PROFILE_PROBE", "https://decline.example.org", "bitte");
    UUID otherType = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO connection_profiles (id, name, source_type, server_url, auth_method,"
            + " ownership, created_at, updated_at, version) VALUES (?, ?, 'S3',"
            + " 'https://s3.example.org', 'PERSONAL_SECRET', 'LIBRARY', now(), now(), 0)",
        otherType,
        "Zugang S3 " + otherType);
    profiles.add(otherType);

    mockMvc
        .perform(
            as("dev-admin", put(ADMIN + "/" + request))
                .content("{\"state\": \"DONE\", \"profileId\": \"" + otherType + "\"}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            as("dev-admin", put(ADMIN + "/" + request))
                .content("{\"state\": \"DECLINED\", \"profileId\": \"" + otherType + "\"}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            as("dev-admin", put(ADMIN + "/" + request))
                .content("{\"state\": \"DECLINED\", \"answer\": \"Dafür gibt es den Zugang A.\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("DECLINED"))
        .andExpect(jsonPath("$.answer").value("Dafür gibt es den Zugang A."))
        .andExpect(jsonPath("$.resolvedAt").exists());

    assertThat(notificationBodies(devUser, request, "CONNECTION_PROFILE_REQUEST_RESOLVED"))
        .singleElement()
        .asString()
        .contains("abgelehnt", "Dafür gibt es den Zugang A.");
  }

  /**
   * An address at the longest admitted length is submitted, declined with the longest answer and
   * served by a new profile; every write it reaches (audit label, notification bodies) holds it.
   */
  @Test
  void anAddressAtTheLongestAdmittedLengthCanBeSubmittedDeclinedAndServed() throws Exception {
    String declined = addressOfLength(300, 'a');
    String served = addressOfLength(300, 'b');
    mockMvc
        .perform(
            as("dev-user", post(SUBMIT))
                .content(body("PROFILE_PROBE", addressOfLength(301, 'c'), null)))
        .andExpect(status().isBadRequest());
    UUID declinedRequest = submit("dev-user", "PROFILE_PROBE", declined, "x".repeat(500));
    UUID servedRequest = submit("dev-user", "PROFILE_PROBE", served, null);

    mockMvc
        .perform(
            as("dev-admin", put(ADMIN + "/" + declinedRequest))
                .content("{\"state\": \"DECLINED\", \"answer\": \"" + "y".repeat(500) + "\"}"))
        .andExpect(status().isOk());
    String created =
        mockMvc
            .perform(
                as("dev-admin", post(PROFILES))
                    .content(
                        """
                        {"name": "%s", "sourceType": "PROFILE_PROBE", "serverUrl": "%s",
                         "authMethod": "NONE", "ownership": "LIBRARY", "fulfillsRequestId": "%s"}
                        """
                            .formatted("Z".repeat(255), served, servedRequest)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    profiles.add(UUID.fromString(JsonPath.read(created, "$.id")));

    assertThat(stateOf(declinedRequest)).isEqualTo("DECLINED");
    assertThat(stateOf(servedRequest)).isEqualTo("DONE");
    assertThat(notificationBodies(devUser, declinedRequest, "CONNECTION_PROFILE_REQUEST_RESOLVED"))
        .singleElement()
        .asString()
        .contains(declined, "y".repeat(500));
  }

  /** Runs on the Liquibase schema: the foreign key takes the request along with the account. */
  @Test
  void deletingTheRequestingAccountRemovesItsRequests() {
    UUID person = insertUser(Organization.DEFAULT_ID);
    UUID request =
        requests
            .submit(
                CurrentUser.of(person, Organization.DEFAULT_ID, SystemRole.USER, "Weg"),
                ProfileProbeSourceConnector.TYPE,
                "https://gone.example.org",
                "Grund")
            .view()
            .request()
            .getId();
    jdbc.update("DELETE FROM notifications WHERE object_id = ?", request);

    jdbc.update("DELETE FROM users WHERE id = ?", person);
    ownUsers.remove(person);

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM connection_profile_requests WHERE id = ?",
                Long.class,
                request))
        .isZero();
  }

  private UUID submit(String user, String type, String url, String reason) throws Exception {
    String response =
        mockMvc
            .perform(as(user, post(SUBMIT)).content(body(type, url, reason)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.state").value("OPEN"))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    return UUID.fromString(JsonPath.read(response, "$.id"));
  }

  /** A valid https address of exactly {@code length} characters, padded with {@code fill}. */
  private static String addressOfLength(int length, char fill) {
    String base = "https://long.example.org/";
    return base + String.valueOf(fill).repeat(length - base.length());
  }

  private static String body(String type, String url, String reason) {
    return "{\"sourceType\": \""
        + type
        + "\", \"serverUrl\": \""
        + url
        + "\""
        + (reason == null ? "" : ", \"reason\": \"" + reason + "\"")
        + "}";
  }

  private List<String> notificationBodies(UUID recipient, UUID request, String type) {
    return jdbc.queryForList(
        "SELECT body FROM notifications WHERE recipient_user_id = ? AND object_id = ? AND type = ?",
        String.class,
        recipient,
        request,
        type);
  }

  private long requestCount(UUID person) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM connection_profile_requests WHERE requested_by = ?",
        Long.class,
        person);
  }

  private String stateOf(UUID request) {
    return jdbc.queryForObject(
        "SELECT state FROM connection_profile_requests WHERE id = ?", String.class, request);
  }

  private UUID userId(String subject) {
    return jdbc.queryForObject("SELECT id FROM users WHERE subject = ?", UUID.class, subject);
  }

  private UUID insertUser(UUID organization) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', ?, 'Testperson', now(), 'USER', ?)",
        id,
        "request-" + id,
        id + "@example.com",
        organization);
    if (organization.equals(Organization.DEFAULT_ID)) {
      ownUsers.add(id);
    }
    return id;
  }

  private void removeRequestsOf(UUID... persons) {
    for (UUID person : persons) {
      if (person == null) {
        continue;
      }
      jdbc.update(
          "DELETE FROM notifications WHERE object_id IN"
              + " (SELECT id FROM connection_profile_requests WHERE requested_by = ?)",
          person);
      jdbc.update("DELETE FROM connection_profile_requests WHERE requested_by = ?", person);
    }
  }

  private static MockHttpServletRequestBuilder as(
      String user, MockHttpServletRequestBuilder request) {
    return request
        .header(DevAuthFilter.DEV_USER_HEADER, user)
        .contentType(MediaType.APPLICATION_JSON);
  }
}
