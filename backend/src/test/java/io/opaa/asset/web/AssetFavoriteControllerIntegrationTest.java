package io.opaa.asset.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.auth.DevAuthFilter;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * {@code PUT/DELETE /api/v1/assets/{assetType}/{assetId}/favorite} (#2095, ADR-0039 Entscheidung
 * 7): a favorite is the caller's own mark on an asset they read by the rights formula - idempotent,
 * never audited, and gone with the asset.
 */
@OpaaIntegrationTest
class AssetFavoriteControllerIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private OwnLibraryFixtures ownLibraryFixtures;

  private final List<UUID> createdLibraryIds = new ArrayList<>();

  @BeforeEach
  void provisionCallers() throws Exception {
    createdLibraryIds.clear();
    mockMvc.perform(get("/api/v1/spaces").with(devUser())).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/spaces").with(devAdmin())).andExpect(status().isOk());
  }

  @AfterEach
  void removeOwnRows() {
    for (UUID libraryId : createdLibraryIds) {
      jdbcTemplate.update("DELETE FROM asset_favorites WHERE asset_id = ?", libraryId);
      jdbcTemplate.update("DELETE FROM audit_log WHERE object_id = ?", libraryId.toString());
      jdbcTemplate.update("DELETE FROM asset_grants WHERE asset_id = ?", libraryId);
      jdbcTemplate.update("DELETE FROM asset_grant_history WHERE asset_id = ?", libraryId);
    }
    ownLibraryFixtures.removeLibraries(createdLibraryIds.toArray(new UUID[0]));
  }

  @Test
  void aReaderMarksAndUnmarksALibraryIdempotently() throws Exception {
    String library = createLibrary(devAdmin());
    UUID reader = userIdOf("dev-user@opaa.local");
    grantToUser(library, reader, "VIEWER");

    mockMvc.perform(mark(library, devUser())).andExpect(status().isNoContent());
    mockMvc.perform(mark(library, devUser())).andExpect(status().isNoContent());
    assertThat(favoriteRows(library, reader)).isEqualTo(1);

    mockMvc.perform(unmark(library, devUser())).andExpect(status().isNoContent());
    assertThat(favoriteRows(library, reader)).isZero();
    mockMvc.perform(unmark(library, devUser())).andExpect(status().isNoContent());
  }

  /** Marking again keeps the time of the first mark. */
  @Test
  void markingAgainKeepsTheFirstMark() throws Exception {
    String library = createLibrary(devAdmin());
    UUID admin = userIdOf("admin@opaa.local");

    mockMvc.perform(mark(library, devAdmin())).andExpect(status().isNoContent());
    jdbcTemplate.update(
        "UPDATE asset_favorites SET created_at = TIMESTAMPTZ '2026-01-01 00:00:00+00'"
            + " WHERE asset_id = ? AND user_id = ?",
        UUID.fromString(library),
        admin);
    mockMvc.perform(mark(library, devAdmin())).andExpect(status().isNoContent());

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT created_at = TIMESTAMPTZ '2026-01-01 00:00:00+00' FROM asset_favorites"
                    + " WHERE asset_id = ? AND user_id = ?",
                Boolean.class,
                UUID.fromString(library),
                admin))
        .isTrue();
  }

  /**
   * An asset the caller cannot read is not confirmed to exist - neither by marking nor unmarking.
   */
  @Test
  void anUnreadableLibraryAnswers404AndStoresNothing() throws Exception {
    String library = createLibrary(devAdmin());

    mockMvc.perform(mark(library, devUser())).andExpect(status().isNotFound());
    mockMvc.perform(unmark(library, devUser())).andExpect(status().isNotFound());

    assertThat(favoriteRows(library, userIdOf("dev-user@opaa.local"))).isZero();
  }

  /** The system administration administers every asset, but administering is not reading. */
  @Test
  void theSystemAdministrationWithoutAGrantCannotMarkALibrary() throws Exception {
    String library = createLibrary(devUser());

    mockMvc.perform(mark(library, devAdmin())).andExpect(status().isNotFound());

    assertThat(favoriteRows(library, userIdOf("admin@opaa.local"))).isZero();
  }

  @Test
  void anUnknownAssetOrAMismatchingTypeAnswers404() throws Exception {
    String library = createLibrary(devAdmin());

    mockMvc
        .perform(
            put("/api/v1/assets/PROMPT_LIBRARY/" + library + "/favorite")
                .with(devAdmin())
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(mark(UUID.randomUUID().toString(), devAdmin()))
        .andExpect(status().isNotFound());

    assertThat(favoriteRows(library, userIdOf("admin@opaa.local"))).isZero();
  }

  /** A favorite is personal order, not an act: no protocol entry and no history (ADR-0039). */
  @Test
  void markingAndUnmarkingWriteNoAuditEntryAndNoHistory() throws Exception {
    String library = createLibrary(devAdmin());
    int auditBefore = auditRowsNaming(library);
    int historyBefore = historyRowsOf(library);

    mockMvc.perform(mark(library, devAdmin())).andExpect(status().isNoContent());
    mockMvc.perform(unmark(library, devAdmin())).andExpect(status().isNoContent());

    assertThat(auditRowsNaming(library)).isEqualTo(auditBefore);
    assertThat(historyRowsOf(library)).isEqualTo(historyBefore);
  }

  /** Unmarking touches only the caller's own mark, never another person's. */
  @Test
  void unmarkingLeavesAnotherPersonsMarkAlone() throws Exception {
    String library = createLibrary(devAdmin());
    UUID reader = userIdOf("dev-user@opaa.local");
    UUID admin = userIdOf("admin@opaa.local");
    grantToUser(library, reader, "VIEWER");
    mockMvc.perform(mark(library, devUser())).andExpect(status().isNoContent());
    mockMvc.perform(mark(library, devAdmin())).andExpect(status().isNoContent());

    mockMvc.perform(unmark(library, devUser())).andExpect(status().isNoContent());

    assertThat(favoriteRows(library, reader)).isZero();
    assertThat(favoriteRows(library, admin)).isEqualTo(1);
  }

  /** The mark goes with its asset - no favorite outlives what it marks. */
  @Test
  void aFavoriteGoesWithItsAsset() throws Exception {
    String library = createLibrary(devAdmin());
    mockMvc.perform(mark(library, devAdmin())).andExpect(status().isNoContent());

    ownLibraryFixtures.removeLibraries(UUID.fromString(library));

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM asset_favorites WHERE asset_id = ?",
                Integer.class,
                UUID.fromString(library)))
        .isZero();
  }

  // -------------------------------------------------------------------------------------------
  // Fixture
  // -------------------------------------------------------------------------------------------

  private static MockHttpServletRequestBuilder mark(String libraryId, RequestPostProcessor caller) {
    return put(favoritePath(libraryId)).with(caller);
  }

  private static MockHttpServletRequestBuilder unmark(
      String libraryId, RequestPostProcessor caller) {
    return MockMvcRequestBuilders.delete(favoritePath(libraryId)).with(caller);
  }

  private static String favoritePath(String libraryId) {
    return "/api/v1/assets/KNOWLEDGE_LIBRARY/" + libraryId + "/favorite";
  }

  private int favoriteRows(String libraryId, UUID userId) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM asset_favorites WHERE asset_id = ? AND user_id = ?",
        Integer.class,
        UUID.fromString(libraryId),
        userId);
  }

  private int auditRowsNaming(String libraryId) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM audit_log WHERE object_id = ?", Integer.class, libraryId);
  }

  private int historyRowsOf(String libraryId) {
    UUID id = UUID.fromString(libraryId);
    return jdbcTemplate.queryForObject(
        "SELECT (SELECT count(*) FROM asset_grant_history WHERE asset_id = ?)"
            + " + (SELECT count(*) FROM asset_visibility_history WHERE asset_id = ?)"
            + " + (SELECT count(*) FROM asset_ownership_history WHERE asset_id = ?)",
        Integer.class,
        id,
        id,
        id);
  }

  private UUID userIdOf(String email) {
    return jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
  }

  private String createLibrary(RequestPostProcessor caller) throws Exception {
    String response =
        mockMvc
            .perform(
                post("/api/v1/libraries")
                    .with(caller)
                    .content("{ \"name\": \"Favoriten Test\", \"sourceType\": \"UPLOAD\" }"))
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
