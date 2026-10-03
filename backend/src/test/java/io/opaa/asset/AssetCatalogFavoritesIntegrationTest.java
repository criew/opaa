package io.opaa.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.permission.AssetGrant;
import io.opaa.permission.AssetGrantRepository;
import io.opaa.prompt.PromptLibrary;
import io.opaa.prompt.PromptLibraryRepository;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnOrganizationFixtures;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Favorites in the catalog (#2095, ADR-0039 Entscheidung 7): the order is fixed, the caller's own
 * favorites first, then by name, and the favorites can be selected alone; the flag and the order
 * only ever reflect the caller's own marks, and a favorite the caller can no longer read is gone
 * from the catalog.
 */
@OpaaIntegrationTest
class AssetCatalogFavoritesIntegrationTest {

  @Autowired private AssetCatalogService catalogService;
  @Autowired private AssetFavoriteService favoriteService;
  @Autowired private PromptLibraryRepository promptLibraryRepository;
  @Autowired private AssetGrantRepository grantRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private OwnOrganizationFixtures ownOrganizationFixtures;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organization;
  private UUID owner;
  private UUID member;
  private UUID colleague;

  @BeforeEach
  void setUp() {
    organization =
        organizationRepository
            .save(new Organization(UUID.randomUUID(), "Katalogfavoriten " + UUID.randomUUID()))
            .getId();
    owner = createUser("Eigentümerin");
    member = createUser("Mitglied");
    colleague = createUser("Kollegin");
  }

  @AfterEach
  void tearDown() {
    jdbcTemplate.update("DELETE FROM asset_favorites WHERE organization_id = ?", organization);
    jdbcTemplate.update("DELETE FROM assets WHERE organization_id = ?", organization);
    jdbcTemplate.update("DELETE FROM asset_grant_history WHERE organization_id = ?", organization);
    jdbcTemplate.update(
        "DELETE FROM asset_ownership_history WHERE organization_id = ?", organization);
    ownOrganizationFixtures.removeOrganizations(organization);
  }

  @Test
  void theCallersFavoritesComeFirstThenByName() {
    UUID alpha = publicLibrary("Alpha");
    UUID beta = publicLibrary("Beta");
    UUID gamma = publicLibrary("Gamma");
    UUID delta = publicLibrary("delta");
    setUpdatedAt(alpha, Instant.now().minus(3, ChronoUnit.DAYS));
    setUpdatedAt(beta, Instant.now().minus(1, ChronoUnit.DAYS));
    setUpdatedAt(gamma, Instant.now().minus(4, ChronoUnit.DAYS));
    setUpdatedAt(delta, Instant.now().minus(2, ChronoUnit.DAYS));
    mark(member, gamma);
    mark(member, delta);

    assertThat(names(list(member, false))).containsExactly("delta", "Gamma", "Alpha", "Beta");
    assertThat(list(member, false).entries())
        .extracting(entry -> entry.asset().getName(), AssetCatalogEntry::favorite)
        .containsExactly(
            tuple("delta", true),
            tuple("Gamma", true),
            tuple("Alpha", false),
            tuple("Beta", false));
  }

  @Test
  void theFavoritesFilterSelectsOnlyTheCallersFavorites() {
    publicLibrary("Alpha");
    UUID beta = publicLibrary("Beta");
    UUID gamma = publicLibrary("Gamma");
    mark(member, gamma);
    mark(member, beta);
    mark(colleague, publicLibrary("Delta"));

    AssetCatalogPage page = list(member, true);

    assertThat(names(page)).containsExactly("Beta", "Gamma");
    assertThat(page.totalElements()).isEqualTo(2);
    assertThat(page.entries()).allMatch(AssetCatalogEntry::favorite);
  }

  /**
   * Favorites first is a total order: the pages neither repeat nor skip an entry, and a favorite
   * that would stand on a later page by name alone leads the first one.
   */
  @Test
  void pagesWithFavoritesNeitherRepeatNorSkipAnEntry() {
    List<String> names = List.of("Alpha", "Beta", "Delta", "Epsilon", "Gamma");
    for (String name : names) {
      UUID id = publicLibrary(name);
      if (name.equals("Gamma")) {
        mark(member, id);
      }
    }

    List<String> paged = new ArrayList<>();
    for (int page = 0; page < 3; page++) {
      AssetCatalogPage result =
          catalogService.list(
              callerOf(member), new AssetCatalogQuery(null, null, null, false), page, 2);
      assertThat(result.totalElements()).isEqualTo(5);
      paged.addAll(names(result));
    }
    assertThat(paged).containsExactly("Gamma", "Alpha", "Beta", "Delta", "Epsilon");
  }

  /**
   * Another person's mark changes neither the caller's order, nor their flags, nor their filter.
   */
  @Test
  void anotherPersonsFavoritesNeverReachTheCaller() {
    publicLibrary("Alpha");
    UUID beta = publicLibrary("Beta");
    mark(colleague, beta);

    assertThat(names(list(member, false))).containsExactly("Alpha", "Beta");
    assertThat(list(member, false).entries()).noneMatch(AssetCatalogEntry::favorite);
    assertThat(list(member, true).totalElements()).isZero();
  }

  /** A favorite the caller may no longer read is gone - from the list, the filter and the count. */
  @Test
  void aFavoriteThatIsNoLongerReadableDisappears() {
    publicLibrary("Alpha");
    UUID closed = libraryReadableBy("Geschlossen", member);
    mark(member, closed);
    jdbcTemplate.update(
        "DELETE FROM asset_grants WHERE asset_id = ? AND subject_user_id = ?", closed, member);

    AssetCatalogPage all = list(member, false);
    AssetCatalogPage favorites = list(member, true);

    assertThat(names(all)).containsExactly("Alpha");
    assertThat(all.totalElements()).isEqualTo(1);
    assertThat(favorites.entries()).isEmpty();
    assertThat(favorites.totalElements()).isZero();
  }

  // -------------------------------------------------------------------------------------------
  // Fixture
  // -------------------------------------------------------------------------------------------

  private AssetCatalogPage list(UUID caller, boolean favoritesOnly) {
    return catalogService.list(
        callerOf(caller), new AssetCatalogQuery(null, null, null, favoritesOnly), 0, 50);
  }

  private void mark(UUID userId, UUID libraryId) {
    favoriteService.mark(PromptLibrary.ASSET_TYPE, libraryId, callerOf(userId));
  }

  private UUID publicLibrary(String name) {
    UUID id = ownedLibrary(name);
    grantRepository.save(
        AssetGrant.forAllAccounts(
            PromptLibrary.ASSET_TYPE, id, organization, AssetRole.VIEWER, null, owner));
    return id;
  }

  private UUID libraryReadableBy(String name, UUID reader) {
    UUID id = ownedLibrary(name);
    grantRepository.save(
        AssetGrant.forUser(
            PromptLibrary.ASSET_TYPE, id, organization, reader, AssetRole.VIEWER, null, owner));
    return id;
  }

  private UUID ownedLibrary(String name) {
    UUID id =
        promptLibraryRepository
            .save(PromptLibrary.ownedByUser(organization, name, null, owner))
            .getId();
    grantRepository.save(
        AssetGrant.forUser(
            PromptLibrary.ASSET_TYPE, id, organization, owner, AssetRole.OWNER, null, owner));
    return id;
  }

  private void setUpdatedAt(UUID assetId, Instant updatedAt) {
    jdbcTemplate.update(
        "UPDATE assets SET updated_at = ? WHERE id = ?", Timestamp.from(updatedAt), assetId);
  }

  private static List<String> names(AssetCatalogPage page) {
    return page.entries().stream().map(entry -> entry.asset().getName()).toList();
  }

  private UUID createUser(String displayName) {
    User user =
        new User(
            "catalog-favorite-" + UUID.randomUUID(),
            "https://issuer.example",
            UUID.randomUUID() + "@example.com",
            displayName);
    user.setOrganizationId(organization);
    return userRepository.save(user).getId();
  }

  private CurrentUser callerOf(UUID userId) {
    return CurrentUser.of(userId, organization, SystemRole.USER, "Sachbearbeitung");
  }
}
