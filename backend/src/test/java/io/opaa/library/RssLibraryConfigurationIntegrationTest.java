package io.opaa.library;

import static io.opaa.library.LibraryCreationBuilder.libraryCreation;
import static io.opaa.library.LibraryUpdateBuilder.libraryUpdate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.ValidationException;
import io.opaa.indexing.spi.ConnectorData;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.SourceTypes;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Content selector and attachment profile as settings of one feed library, through the
 * library service against the Flyway schema: stored normalised, refused when invalid, named in the
 * audit without their value.
 */
@OpaaIntegrationTest
class RssLibraryConfigurationIntegrationTest {

  private static final URI FEED = URI.create("https://presse.rheinfurt.example/meldungen.rss");

  @Autowired private KnowledgeLibraryService libraryService;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationId;
  private final List<UUID> userIds = new ArrayList<>();

  @BeforeEach
  void setUp() {
    organizationId =
        organizationRepository
            .save(new Organization(UUID.randomUUID(), "RSS-Test-Org " + UUID.randomUUID()))
            .getId();
  }

  @AfterEach
  void tearDown() {
    List<KnowledgeLibrary> own =
        libraryRepository.findAll().stream()
            .filter(l -> organizationId.equals(l.getOrganizationId()))
            .toList();
    libraryRepository.deleteAll(own);
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    jdbcTemplate.update(
        "DELETE FROM asset_grant_history WHERE subject_user_id IN (SELECT id FROM users WHERE"
            + " organization_id = ?)",
        organizationId);
    jdbcTemplate.update(
        "DELETE FROM asset_ownership_history WHERE organization_id = ?", organizationId);
    for (UUID userId : userIds) {
      userRepository.deleteById(userId);
    }
    organizationRepository.deleteById(organizationId);
  }

  @Test
  void aFeedLibraryKeepsItsOwnSelectorAndProfileNormalised() {
    CurrentUser caller = currentUser(user());

    KnowledgeLibrary library =
        libraryService
            .createLibrary(
                libraryCreation("Pressemitteilungen", SourceTypes.RSS_FEED)
                    .sourceUrl(FEED)
                    .sourceSettings(
                        ConnectorData.of(
                            Map.of(
                                "mainContentSelector", "  #inhalt  ", "attachmentProfile", "gsb")))
                    .build(),
                caller)
            .library();

    KnowledgeLibrary reloaded = libraryRepository.findById(library.getId()).orElseThrow();
    assertThat(ConnectorData.storedIn(reloaded).asMap())
        .containsOnly(
            Map.entry("mainContentSelector", "#inhalt"), Map.entry("attachmentProfile", "GSB"));
  }

  @Test
  void anInvalidSelectorIsRefusedOnCreateAndOnChange() {
    CurrentUser caller = currentUser(user());

    assertThatThrownBy(
            () ->
                libraryService.createLibrary(
                    libraryCreation("Feed", SourceTypes.RSS_FEED)
                        .sourceUrl(FEED)
                        .sourceSettings(ConnectorData.of(Map.of("mainContentSelector", "div[")))
                        .build(),
                    caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("sourceSettings.mainContentSelector");

    UUID libraryId = createFeed(caller);
    assertThatThrownBy(
            () ->
                libraryService.updateLibrary(
                    libraryId,
                    libraryUpdate("Feed")
                        .sourceSettings(
                            ConnectorData.of(Map.of("mainContentSelector", "p:matches(a+)")))
                        .build(),
                    caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("reguläre Ausdrücke");
  }

  @Test
  void aChangeIsAuditedByFieldNameWithoutItsValue() {
    CurrentUser caller = currentUser(user());
    UUID libraryId = createFeed(caller);

    libraryService.updateLibrary(
        libraryId,
        libraryUpdate("Feed")
            .sourceSettings(ConnectorData.of(Map.of("mainContentSelector", ".meldung")))
            .build(),
        caller);

    List<String> audit =
        jdbcTemplate.queryForList(
            "SELECT after FROM audit_log WHERE object_id = ? AND event_type = ?",
            String.class,
            libraryId.toString(),
            "LIBRARY_SOURCE_UPDATED");
    assertThat(audit)
        .singleElement()
        .satisfies(
            payload ->
                assertThat(payload).contains("mainContentSelector").doesNotContain(".meldung"));
  }

  @Test
  void anEmptyObjectReturnsTheLibraryToTheFactoryDefaults() {
    CurrentUser caller = currentUser(user());
    UUID libraryId =
        libraryService
            .createLibrary(
                libraryCreation("Feed", SourceTypes.RSS_FEED)
                    .sourceUrl(FEED)
                    .sourceSettings(ConnectorData.of(Map.of("attachmentProfile", "GSB")))
                    .build(),
                caller)
            .library()
            .getId();

    libraryService.updateLibrary(
        libraryId,
        libraryUpdate("Feed").sourceSettings(ConnectorData.of(Map.of())).build(),
        caller);

    ConnectorData stored =
        ConnectorData.storedIn(libraryRepository.findById(libraryId).orElseThrow());
    assertThat(stored == null || stored.isEmpty()).isTrue();
  }

  private UUID createFeed(CurrentUser caller) {
    return libraryService
        .createLibrary(
            libraryCreation("Feed", SourceTypes.RSS_FEED).sourceUrl(FEED).build(), caller)
        .library()
        .getId();
  }

  private UUID user() {
    User user = new User("rss-" + UUID.randomUUID(), "https://issuer.example", null, "RSS Tester");
    user.setOrganizationId(organizationId);
    UUID id = userRepository.save(user).getId();
    userIds.add(id);
    return id;
  }

  private CurrentUser currentUser(UUID userId) {
    return CurrentUser.of(userId, organizationId, SystemRole.USER, "RSS Tester");
  }
}
