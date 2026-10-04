package io.opaa.test;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.auth.DevAuthFilter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Profiles and libraries of a shipped connector through the HTTP API, as the system administration
 * creates them, with a run awaited to its end; {@link #cleanUp} removes what was created. A plain
 * object per test class, so it adds no bean and splits no context.
 */
public final class ProfileLibraries {

  /** The account every request runs as unless named otherwise. */
  public static final String ADMIN = "dev-admin";

  private static final String PROFILES = "/api/v1/admin/connection-profiles";
  private static final Set<String> FINISHED = Set.of("COMPLETED", "FAILED");

  private final MockMvc mockMvc;
  private final JdbcTemplate jdbc;
  private final OwnLibraryFixtures fixtures;
  private final List<UUID> profiles = new ArrayList<>();
  private final List<UUID> libraries = new ArrayList<>();

  public ProfileLibraries(MockMvc mockMvc, JdbcTemplate jdbc, OwnLibraryFixtures fixtures) {
    this.mockMvc = mockMvc;
    this.jdbc = jdbc;
    this.fixtures = fixtures;
  }

  /** Creates the profile {@code json} describes, released to all accounts. */
  public UUID createProfile(String json) throws Exception {
    String body =
        mockMvc
            .perform(as(ADMIN, post(PROFILES)).content(json))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
    profiles.add(id);
    ConnectorReleases.releaseToAllAccounts(jdbc, "PROFILE:" + id);
    return id;
  }

  /** {@code POST /api/v1/libraries} with {@code json}, whatever it answers. */
  public ResultActions postLibrary(String json) throws Exception {
    return mockMvc.perform(as(ADMIN, post("/api/v1/libraries")).content(json));
  }

  /** Creates the library {@code json} describes; it is removed again by {@link #cleanUp}. */
  public UUID createLibrary(String json) throws Exception {
    String body =
        postLibrary(json)
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
    libraries.add(id);
    return id;
  }

  /** Starts a run of {@code library} and returns its status JSON once it has ended. */
  public String run(UUID library) throws Exception {
    mockMvc
        .perform(as(ADMIN, post("/api/v1/libraries/" + library + "/indexing")))
        .andExpect(status().isAccepted());
    String[] status = new String[1];
    await()
        .atMost(Duration.ofSeconds(60))
        .until(
            () -> {
              status[0] =
                  mockMvc
                      .perform(as(ADMIN, get("/api/v1/libraries/" + library + "/indexing/status")))
                      .andReturn()
                      .getResponse()
                      .getContentAsString(StandardCharsets.UTF_8);
              return FINISHED.contains(JsonPath.<String>read(status[0], "$.status"));
            });
    return status[0];
  }

  /** The stored value of {@code column} of {@code library}, as text. */
  public String stored(UUID library, String column) {
    return jdbc.queryForObject(
        "SELECT " + column + "::text FROM knowledge_libraries WHERE id = ?", String.class, library);
  }

  /** Removes the libraries, then the profiles and their releases. */
  public void cleanUp() {
    fixtures.removeLibraries(libraries.toArray(UUID[]::new));
    libraries.clear();
    for (UUID profile : profiles) {
      ConnectorReleases.withdraw(jdbc, "PROFILE:" + profile);
      jdbc.update("DELETE FROM connection_profiles WHERE id = ?", profile);
    }
    profiles.clear();
  }

  public static MockHttpServletRequestBuilder as(
      String user, MockHttpServletRequestBuilder request) {
    return request
        .header(DevAuthFilter.DEV_USER_HEADER, user)
        .contentType(MediaType.APPLICATION_JSON);
  }
}
