package io.opaa.asset.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.auth.DevAuthFilter;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import io.opaa.test.OwnOrganizationFixtures;
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
 * never audited, and gone with the asset. Removing one's own mark is always possible and always
 * answers 204.
 */
@OpaaIntegrationTest
class AssetFavoriteControllerIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private OwnLibraryFixtures ownLibraryFixtures;
  @Autowired private OwnOrganizationFixtures ownOrganizationFixtures;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private KnowledgeLibraryRepository knowledgeLibraryRepository;

  private final List<UUID> createdLibraryIds = new ArrayList<>();
  private final List<UUID> createdOrganizationIds = new ArrayList<>();

  @BeforeEach
  void provisionCallers() throws Exception {
    createdLibraryIds.clear();
    createdOrganizationIds.clear();
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
    ownOrganizationFixtures.removeOrganizations(createdOrganizationIds.toArray(new UUID[0]));
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

  @Test
  void anUnreadableLibraryCannotBeMarked() throws Exception {
    String library = createLibrary(devAdmin());

    mockMvc.perform(mark(library, devUser())).andExpect(status().isNotFound());

    assertThat(favoriteRows(library, userIdOf("dev-user@opaa.local"))).isZero();
  }

  /** The system administration administers every asset, but administering is not reading. */
  @Test
  void theSystemAdministrationWithoutAGrantCannotMarkALibrary() throws Exception {
    String library = createLibrary(devUser());

    mockMvc.perform(mark(library, devAdmin())).andExpect(status().isNotFound());

    assertThat(favoriteRows(library, userIdOf("admin@opaa.local"))).isZero();
  }

  /**
   * Marking an asset the caller cannot read never confirms that it exists: unknown, unreadable and
   * foreign assets answer the same 404, and so does an asset under another type's path.
   */
  @Test
  void markingAnswersTheSame404ForEveryAssetTheCallerCannotRead() throws Exception {
    String unreadable = createLibrary(devAdmin());
    String foreign = createForeignLibrary().toString();

    String unknownAnswer = notFoundMessage(mark(UUID.randomUUID().toString(), devUser()));
    assertThat(notFoundMessage(mark(unreadable, devUser()))).isEqualTo(unknownAnswer);
    assertThat(notFoundMessage(mark(foreign, devUser()))).isEqualTo(unknownAnswer);

    String unknownPromptLibrary =
        notFoundMessage(markAs("PROMPT_LIBRARY", UUID.randomUUID().toString(), devAdmin()));
    assertThat(notFoundMessage(markAs("PROMPT_LIBRARY", unreadable, devAdmin())))
        .isEqualTo(unknownPromptLibrary);

    assertThat(favoriteRows(unreadable, userIdOf("dev-user@opaa.local"))).isZero();
    assertThat(favoriteRows(unreadable, userIdOf("admin@opaa.local"))).isZero();
  }

  /** A person may always remove their own mark, even once they can no longer read the asset. */
  @Test
  void unmarkingWorksAfterTheReadRightIsGone() throws Exception {
    String library = createLibrary(devAdmin());
    UUID reader = userIdOf("dev-user@opaa.local");
    grantToUser(library, reader, "VIEWER");
    mockMvc.perform(mark(library, devUser())).andExpect(status().isNoContent());
    revokeUserGrant(library, reader);

    mockMvc.perform(unmark(library, devUser())).andExpect(status().isNoContent());

    assertThat(favoriteRows(library, reader)).isZero();
  }

  /**
   * Unmarking checks nothing and answers 204 for every asset, so its answer confirms none; a path
   * naming another type removes nothing.
   */
  @Test
  void unmarkingAnswers204ForUnknownAndMismatchingAssetsAndRemovesNothingElse() throws Exception {
    String library = createLibrary(devAdmin());
    UUID admin = userIdOf("admin@opaa.local");
    mockMvc.perform(mark(library, devAdmin())).andExpect(status().isNoContent());

    mockMvc
        .perform(unmark(UUID.randomUUID().toString(), devAdmin()))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(
            MockMvcRequestBuilders.delete("/api/v1/assets/PROMPT_LIBRARY/" + library + "/favorite")
                .with(devAdmin()))
        .andExpect(status().isNoContent());

    assertThat(favoriteRows(library, admin)).isEqualTo(1);
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

  private static MockHttpServletRequestBuilder markAs(
      String assetType, String assetId, RequestPostProcessor caller) {
    return put("/api/v1/assets/" + assetType + "/" + assetId + "/favorite").with(caller);
  }

  /** The user-facing message of a 404 - what a caller could tell two refusals apart by. */
  private String notFoundMessage(MockHttpServletRequestBuilder request) throws Exception {
    String body =
        mockMvc
            .perform(request)
            .andExpect(status().isNotFound())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    return JsonPath.read(body, "$.error");
  }

  private void revokeUserGrant(String libraryId, UUID userId) throws Exception {
    UUID grantId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM asset_grants WHERE asset_id = ? AND subject_user_id = ?",
            UUID.class,
            UUID.fromString(libraryId),
            userId);
    mockMvc
        .perform(
            MockMvcRequestBuilders.delete(
                    "/api/v1/assets/KNOWLEDGE_LIBRARY/" + libraryId + "/grants/" + grantId)
                .with(devAdmin()))
        .andExpect(status().isNoContent());
  }

  /** A library in an organization of its own, readable by its owner there and by nobody here. */
  private UUID createForeignLibrary() {
    UUID organization =
        organizationRepository
            .save(new Organization(UUID.randomUUID(), "Fremde Favoriten " + UUID.randomUUID()))
            .getId();
    createdOrganizationIds.add(organization);
    User owner =
        new User(
            "favorite-" + UUID.randomUUID(),
            "https://issuer.example",
            UUID.randomUUID() + "@example.com",
            "Fremde Eigentümerin");
    owner.setOrganizationId(organization);
    UUID ownerId = userRepository.save(owner).getId();
    return knowledgeLibraryRepository
        .save(KnowledgeLibrary.ownedByUser(organization, "Fremd", null, ownerId))
        .getId();
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
