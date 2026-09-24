package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.auth.DevAuthFilter;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * {@code POST /api/v1/assets/{assetType}/{assetId}/transfer-ownership} (#1941): who may hand one
 * asset over, who may take it, and that the four things an owner change consists of - the owner
 * columns, the grant ownership goes with, the ownership interval and the audit entry - move
 * together.
 */
@OpaaIntegrationTest
class AssetOwnershipTransferControllerIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private OwnLibraryFixtures ownLibraryFixtures;

  private final List<UUID> createdLibraryIds = new ArrayList<>();
  private final List<UUID> createdGroupIds = new ArrayList<>();

  @BeforeEach
  void provisionCallers() throws Exception {
    createdLibraryIds.clear();
    createdGroupIds.clear();
    mockMvc.perform(get("/api/v1/spaces").with(devUser())).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/spaces").with(devAdmin())).andExpect(status().isOk());
  }

  @AfterEach
  void removeOwnRows() {
    for (UUID libraryId : createdLibraryIds) {
      jdbcTemplate.update("DELETE FROM audit_log WHERE object_id = ?", libraryId.toString());
      jdbcTemplate.update("DELETE FROM asset_grants WHERE asset_id = ?", libraryId);
      jdbcTemplate.update("DELETE FROM asset_grant_history WHERE asset_id = ?", libraryId);
    }
    for (UUID groupId : createdGroupIds) {
      jdbcTemplate.update("DELETE FROM audit_log WHERE object_id = ?", groupId.toString());
      jdbcTemplate.update("DELETE FROM asset_grant_history WHERE subject_group_id = ?", groupId);
      jdbcTemplate.update("DELETE FROM group_membership_history WHERE group_id = ?", groupId);
    }
    ownLibraryFixtures.removeLibraries(createdLibraryIds.toArray(new UUID[0]));
    for (UUID groupId : createdGroupIds) {
      jdbcTemplate.update("DELETE FROM group_stewards WHERE group_id = ?", groupId);
      jdbcTemplate.update("DELETE FROM group_memberships WHERE group_id = ?", groupId);
      jdbcTemplate.update("DELETE FROM groups WHERE id = ?", groupId);
    }
  }

  /** The whole move in one pass: owner column, grants on both sides, interval and audit entry. */
  @Test
  void handingALibraryToAnotherPersonMovesOwnerColumnGrantIntervalAndAudit() throws Exception {
    String library = createLibrary(devAdmin());
    UUID admin = userIdOf("admin@opaa.local");
    UUID successor = userIdOf("dev-user@opaa.local");

    mockMvc
        .perform(transfer(library, devAdmin(), "USER", successor))
        .andExpect(status().isNoContent());

    assertThat(ownerUserIdOf(library)).isEqualTo(successor);
    assertThat(roleOfUserGrant(library, successor)).isEqualTo("OWNER");
    assertThat(roleOfUserGrant(library, admin)).isNull();
    assertThat(openOwnerIntervalUserIdOf(library)).isEqualTo(successor);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log WHERE object_id = ? AND event_type ="
                    + " 'ASSET_OWNER_CHANGED'",
                Integer.class,
                library))
        .isEqualTo(1);
    // The successor now reads their own library through the moved grant, not through the
    // administration - the cache of the access formula was invalidated with the transfer.
    mockMvc
        .perform(get("/api/v1/libraries/" + library).with(devUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ownerId").value(successor.toString()))
        .andExpect(jsonPath("$.myRole").value("OWNER"));
  }

  /** The new owner's display name reaches every reader of the library (#1941). */
  @Test
  void theDetailResponseNamesTheOwner() throws Exception {
    String library = createLibrary(devAdmin());

    mockMvc
        .perform(get("/api/v1/libraries/" + library).with(devAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ownerName").value(displayNameOf("admin@opaa.local")));
  }

  /** MANAGER is not enough - handing an asset on is the owner's own act. */
  @Test
  void aManagerMayNotHandTheLibraryOn() throws Exception {
    String library = createLibrary(devAdmin());
    UUID caller = userIdOf("dev-user@opaa.local");
    grantToUser(library, caller, "MANAGER");

    mockMvc.perform(transfer(library, devUser(), "USER", caller)).andExpect(status().isForbidden());
    assertThat(ownerUserIdOf(library)).isEqualTo(userIdOf("admin@opaa.local"));
  }

  /** Nobody who cannot reach the library at all learns that it exists (#436). */
  @Test
  void aCallerWithoutAnyRoleGets404() throws Exception {
    String library = createLibrary(devAdmin());

    mockMvc
        .perform(transfer(library, devUser(), "USER", userIdOf("dev-user@opaa.local")))
        .andExpect(status().isNotFound());
  }

  /** An unknown person is "not found", never "forbidden" - the same answer a foreign one gets. */
  @Test
  void anUnknownTargetPersonIsNotFound() throws Exception {
    String library = createLibrary(devAdmin());

    mockMvc
        .perform(transfer(library, devAdmin(), "USER", UUID.randomUUID()))
        .andExpect(status().isNotFound());
    assertThat(ownerUserIdOf(library)).isEqualTo(userIdOf("admin@opaa.local"));
  }

  /** Handing the asset to its current owner is a no-op, not an error - the call is idempotent. */
  @Test
  void handingTheLibraryToItsCurrentOwnerChangesNothing() throws Exception {
    String library = createLibrary(devAdmin());
    UUID admin = userIdOf("admin@opaa.local");

    mockMvc.perform(transfer(library, devAdmin(), "USER", admin)).andExpect(status().isNoContent());

    assertThat(ownerUserIdOf(library)).isEqualTo(admin);
    assertThat(roleOfUserGrant(library, admin)).isEqualTo("OWNER");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log WHERE object_id = ? AND event_type ="
                    + " 'ASSET_OWNER_CHANGED'",
                Integer.class,
                library))
        .isZero();
  }

  /** A group never holds OWNER - it takes the MANAGER grant ownership goes with. */
  @Test
  void aGroupOwnerTakesTheManagerGrantNotOwner() throws Exception {
    String library = createLibrary(devAdmin());
    UUID group = createInternalGroupWithAdminMember();

    mockMvc
        .perform(transfer(library, devAdmin(), "GROUP", group))
        .andExpect(status().isNoContent());

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT owner_group_id FROM assets WHERE id = ?",
                UUID.class,
                UUID.fromString(library)))
        .isEqualTo(group);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT role FROM asset_grants WHERE asset_id = ? AND subject_group_id = ?",
                String.class,
                UUID.fromString(library),
                group))
        .isEqualTo("MANAGER");
  }

  // -------------------------------------------------------------------------------------------
  // Fixture
  // -------------------------------------------------------------------------------------------

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder transfer(
      String libraryId, RequestPostProcessor caller, String ownerType, UUID ownerId) {
    return post("/api/v1/assets/KNOWLEDGE_LIBRARY/" + libraryId + "/transfer-ownership")
        .with(caller)
        .content("{\"ownerType\":\"" + ownerType + "\",\"ownerId\":\"" + ownerId + "\"}");
  }

  private UUID userIdOf(String email) {
    return jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
  }

  private String displayNameOf(String email) {
    return jdbcTemplate.queryForObject(
        "SELECT display_name FROM users WHERE email = ?", String.class, email);
  }

  private UUID ownerUserIdOf(String libraryId) {
    return jdbcTemplate.queryForObject(
        "SELECT owner_user_id FROM assets WHERE id = ?", UUID.class, UUID.fromString(libraryId));
  }

  private String roleOfUserGrant(String libraryId, UUID userId) {
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT role FROM asset_grants WHERE asset_id = ? AND subject_user_id = ?",
            UUID.fromString(libraryId),
            userId);
    return rows.isEmpty() ? null : (String) rows.get(0).get("role");
  }

  private UUID openOwnerIntervalUserIdOf(String libraryId) {
    return jdbcTemplate.queryForObject(
        "SELECT owner_user_id FROM asset_ownership_history WHERE asset_id = ? AND valid_to IS NULL",
        UUID.class,
        UUID.fromString(libraryId));
  }

  /** Creating a group makes the caller its steward, not its member - and ownership needs both. */
  private UUID createInternalGroupWithAdminMember() throws Exception {
    String response =
        mockMvc
            .perform(
                post("/api/v1/groups")
                    .with(devAdmin())
                    .content("{\"name\":\"Referat Eigentum " + UUID.randomUUID() + "\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    UUID groupId = UUID.fromString(JsonPath.read(response, "$.id"));
    createdGroupIds.add(groupId);
    mockMvc
        .perform(
            post("/api/v1/groups/" + groupId + "/members")
                .with(devAdmin())
                .content("{\"userId\":\"" + userIdOf("admin@opaa.local") + "\"}"))
        .andExpect(status().isCreated());
    return groupId;
  }

  private String createLibrary(RequestPostProcessor caller) throws Exception {
    String response =
        mockMvc
            .perform(
                post("/api/v1/libraries")
                    .with(caller)
                    .content(
                        "{ \"name\": \"Eigentumsübergabe Test\", \"sourceType\": \"UPLOAD\" }"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    String id = JsonPath.read(response, "$.id");
    createdLibraryIds.add(UUID.fromString(id));
    return id;
  }

  private void grantToUser(String libraryId, UUID userId, String role) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/assets/KNOWLEDGE_LIBRARY/" + libraryId + "/grants")
                .with(devAdmin())
                .content(
                    "{\"subjectType\":\"USER\",\"subjectId\":\""
                        + userId
                        + "\",\"role\":\""
                        + role
                        + "\"}"))
        .andExpect(status().isOk());
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
