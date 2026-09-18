package io.opaa.externalaccess.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.api.types.AssetRole;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.LibraryOwnerType;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.DevAuthFilter;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ValidationException;
import io.opaa.externalaccess.ExternalAccessSettings;
import io.opaa.externalaccess.ExternalAccessSettingsService;
import io.opaa.library.AssetGrant;
import io.opaa.library.AssetGrantRepository;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.KnowledgeLibraryService;
import io.opaa.library.LibraryAccessService;
import io.opaa.library.LibraryCreation;
import io.opaa.security.LocalAuthKeyService;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The endpoints of the personal access tokens (#1718, ADR-0035 Entscheidung 2): issuance with the
 * value shown exactly once, the two lists and their deliberate asymmetry, revocation, the blocking
 * levers - and the rules that are the point of the feature rather than mere validation (mandatory
 * expiry under a ceiling, mandatory name and selection, no path that edits a selection, a library
 * that is not readable or not released is not selectable, a release that comes back does not revive
 * an extinguished entry).
 *
 * <p>The two seams into the neighbouring issues - the installation switch (#1717) and the library
 * release (#1731) - are exercised by constructing the services with a closed gate respectively a
 * refusing release, which is exactly how the real implementations will plug in.
 */
@OpaaIntegrationTest
class ExternalAccessTokenIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private OwnLibraryFixtures ownLibraryFixtures;
  @Autowired private UserRepository users;
  @Autowired private KnowledgeLibraryService libraryService;
  @Autowired private KnowledgeLibraryRepository libraries;
  @Autowired private LibraryAccessService libraryAccess;
  @Autowired private AssetGrantRepository grants;
  @Autowired private ExternalAccessTokenRepository tokens;
  @Autowired private ExternalAccessTokenService tokenService;
  @Autowired private LocalAuthKeyService keys;
  @Autowired private AuditEventRecorder audit;
  @Autowired private ExternalAccessSettingsService settings;
  @Autowired private Clock clock;

  private UUID libraryId;
  private UUID foreignLibraryId;
  private User owner;
  private User administrator;

  @BeforeEach
  void setUp() throws Exception {
    // Provisions both development accounts through the path a real request takes.
    mockMvc.perform(get("/api/v1/auth/me").with(devUser("dev-user"))).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/auth/me").with(devUser("dev-admin"))).andExpect(status().isOk());
    owner = users.findBySubjectAndIssuer("dev-user", "opaa-dev").orElseThrow();
    administrator = users.findBySubjectAndIssuer("dev-admin", "opaa-dev").orElseThrow();
    libraryId = createLibrary(owner, "Zugangstoken-Testbibliothek");
    foreignLibraryId = createLibrary(administrator, "Fremde Bibliothek");
    removeOwnTokens();
    setChannelEnabled(true);
  }

  /**
   * The installation switch of #1717. The settings row is installation-wide, so {@code
   * SeededRowRestorer} puts it back after every method - this never leaks into a sibling class.
   */
  private void setChannelEnabled(boolean enabled) {
    ExternalAccessSettings.Values values = settings.current().values();
    settings.update(
        CurrentUser.of(
            administrator.getId(),
            administrator.getOrganizationId(),
            administrator.getSystemRole(),
            "Systemverwaltung"),
        new ExternalAccessSettingsService.Update(
            enabled,
            values.tokenMaxLifetimeDays(),
            values.tokenRateLimitPerHour(),
            values.allowedCidrs(),
            values.massRetrievalAlertThreshold(),
            values.serverInstructions()));
  }

  @AfterEach
  void tearDown() {
    // audit_log is append-only for the application account and is therefore never cleaned here;
    // every assertion below is scoped to this class's own object ids.
    removeOwnTokens();
    ownLibraryFixtures.removeLibraries(libraryId, foreignLibraryId);
  }

  private void removeOwnTokens() {
    jdbcTemplate.update(
        "DELETE FROM external_access_tokens WHERE user_id IN (?, ?)",
        owner.getId(),
        administrator.getId());
  }

  private UUID createLibrary(User user, String name) {
    return libraryService
        .createLibrary(
            new LibraryCreation(
                name,
                null,
                LibraryOwnerType.USER,
                user.getId(),
                LibraryVisibility.PRIVATE,
                false,
                DocumentSourceType.UPLOAD,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null),
            CurrentUser.of(user.getId(), user.getOrganizationId(), user.getSystemRole(), name))
        .library()
        .getId();
  }

  private RequestPostProcessor devUser(String subject) {
    return request -> {
      request.addHeader(DevAuthFilter.DEV_USER_HEADER, subject);
      request.setContentType(MediaType.APPLICATION_JSON_VALUE);
      return request;
    };
  }

  private String createBody(UUID library, Instant expiresAt) {
    return """
        {"name":"Claude Code auf dem Dienstrechner","libraryIds":["%s"],"expiresAt":"%s"}"""
        .formatted(library, expiresAt);
  }

  private String issue() throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/external-access/tokens")
                .with(devUser("dev-user"))
                .content(createBody(libraryId, clock.instant().plus(Duration.ofDays(30)))))
        .andExpect(status().isCreated())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private UUID issueTokenId() throws Exception {
    return UUID.fromString(JsonPath.read(issue(), "$.id"));
  }

  /** The production service with the release seam of #1731 replaced; everything else is real. */
  private ExternalAccessTokenService serviceWith(ExternalAccessLibraryRelease release) {
    return new ExternalAccessTokenService(
        tokens, libraries, libraryAccess, release, settings, keys, audit, clock);
  }

  private ExternalAccessTokenScopeService scopeWith(ExternalAccessLibraryRelease release) {
    return new ExternalAccessTokenScopeService(tokens, libraryAccess, settings, release, clock);
  }

  private static final ExternalAccessLibraryRelease ALL_RELEASED = Set::copyOf;
  private static final ExternalAccessLibraryRelease NONE_RELEASED = ids -> Set.of();

  @Test
  void issuesATokenWhoseValueIsShownOnceAndStoredNowhereInClear() throws Exception {
    String body = issue();
    String rawValue = JsonPath.read(body, "$.token");
    UUID tokenId = UUID.fromString(JsonPath.read(body, "$.id"));

    assertThat(rawValue).startsWith(ExternalAccessTokenValues.VALUE_PREFIX);
    ExternalAccessToken stored = tokens.findById(tokenId).orElseThrow();
    assertThat(stored.getTokenLookupHash()).isNotEqualTo(rawValue).hasSize(64);
    assertThat(JsonPath.<String>read(body, "$.prefix")).isEqualTo(stored.getTokenPrefix());

    String row =
        jdbcTemplate.queryForObject(
            "SELECT CAST(t AS text) FROM external_access_tokens t WHERE t.id = ?",
            String.class,
            tokenId);
    assertThat(row).doesNotContain(rawValue.substring(stored.getTokenPrefix().length()));

    String list =
        mockMvc
            .perform(get("/api/v1/external-access/tokens").with(devUser("dev-user")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(list).doesNotContain(rawValue).contains(stored.getTokenPrefix());
  }

  @Test
  void issuanceIsAuditedWithoutTheTokenName() throws Exception {
    UUID tokenId = issueTokenId();

    List<String> payloads =
        jdbcTemplate.queryForList(
            "SELECT coalesce(CAST(after AS text), '') FROM audit_log"
                + " WHERE event_type = ? AND object_id = ?",
            String.class,
            AuditEventType.API_TOKEN_ISSUED.name(),
            tokenId.toString());

    assertThat(payloads).hasSize(1);
    assertThat(payloads.get(0))
        .contains(libraryId.toString())
        .doesNotContain("Claude Code auf dem Dienstrechner");
  }

  @Test
  void theUseOfATokenWritesNoAuditEvent() throws Exception {
    UUID tokenId = issueTokenId();
    long before = auditRowsFor(tokenId);

    tokenService.recordUse(tokenId);
    tokenService.recordUse(tokenId);

    assertThat(auditRowsFor(tokenId)).isEqualTo(before);
    assertThat(tokens.findById(tokenId).orElseThrow().getLastUsedOn()).isNotNull();
  }

  private long auditRowsFor(UUID tokenId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM audit_log WHERE object_id = ?", Long.class, tokenId.toString());
  }

  @Test
  void refusesAnIssuanceWithoutAnExpiryOrBeyondTheCeiling() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/external-access/tokens")
                .with(devUser("dev-user"))
                .content("{\"name\":\"Ohne Ablauf\",\"libraryIds\":[\"" + libraryId + "\"]}"))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(
            post("/api/v1/external-access/tokens")
                .with(devUser("dev-user"))
                .content(createBody(libraryId, clock.instant().plus(Duration.ofDays(91)))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(Matchers.containsString("90")));
  }

  @Test
  void refusesAnIssuanceWithoutANameOrWithoutALibrary() throws Exception {
    Instant expiresAt = clock.instant().plus(Duration.ofDays(10));

    mockMvc
        .perform(
            post("/api/v1/external-access/tokens")
                .with(devUser("dev-user"))
                .content(
                    "{\"name\":\"  \",\"libraryIds\":[\""
                        + libraryId
                        + "\"],\"expiresAt\":\""
                        + expiresAt
                        + "\"}"))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(
            post("/api/v1/external-access/tokens")
                .with(devUser("dev-user"))
                .content(
                    "{\"name\":\"Ohne Bibliothek\",\"libraryIds\":[],\"expiresAt\":\""
                        + expiresAt
                        + "\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void refusesALibraryThePersonMayNotRead() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/external-access/tokens")
                .with(devUser("dev-user"))
                .content(createBody(foreignLibraryId, clock.instant().plus(Duration.ofDays(10)))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void refusesALibraryThatIsReadableButNotReleased() {
    assertThatThrownBy(
            () ->
                serviceWith(NONE_RELEASED)
                    .issue(
                        owner.getId(),
                        owner.getOrganizationId(),
                        "Nicht freigegeben",
                        List.of(libraryId),
                        clock.instant().plus(Duration.ofDays(5))))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void refusesAnIssuanceWhileTheChannelIsClosed() {
    setChannelEnabled(false);

    assertThatThrownBy(
            () ->
                serviceWith(ALL_RELEASED)
                    .issue(
                        owner.getId(),
                        owner.getOrganizationId(),
                        "Kanal zu",
                        List.of(libraryId),
                        clock.instant().plus(Duration.ofDays(5))))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void hasNoPathThatChangesTheSelectionOfAnIssuedToken() throws Exception {
    UUID tokenId = issueTokenId();

    mockMvc
        .perform(
            put("/api/v1/external-access/tokens/" + tokenId)
                .with(devUser("dev-user"))
                .content(createBody(foreignLibraryId, clock.instant().plus(Duration.ofDays(5)))))
        .andExpect(
            result -> assertThat(result.getResponse().getStatus()).isGreaterThanOrEqualTo(400));
    mockMvc
        .perform(
            put("/api/v1/external-access/tokens/" + tokenId + "/libraries")
                .with(devUser("dev-user"))
                .content("{\"libraryIds\":[]}"))
        .andExpect(
            result -> assertThat(result.getResponse().getStatus()).isGreaterThanOrEqualTo(400));

    assertThat(tokens.findById(tokenId).orElseThrow().getSelectedLibraryIds())
        .containsExactly(libraryId);
  }

  @Test
  void revocationByThePersonClearsTheDayOfUse() throws Exception {
    UUID tokenId = issueTokenId();
    tokenService.recordUse(tokenId);
    assertThat(tokens.findById(tokenId).orElseThrow().getLastUsedOn()).isNotNull();

    mockMvc
        .perform(delete("/api/v1/external-access/tokens/" + tokenId).with(devUser("dev-user")))
        .andExpect(status().isNoContent());

    ExternalAccessToken revoked = tokens.findById(tokenId).orElseThrow();
    assertThat(revoked.getRevokedAt()).isNotNull();
    assertThat(revoked.getLastUsedOn()).isNull();
    assertThat(revoked.getRevocationReason()).isEqualTo(ExternalAccessTokenRevocationReason.OWNER);
  }

  @Test
  void aForeignTokenIsNotFoundRatherThanForbidden() throws Exception {
    UUID tokenId = issueTokenId();

    mockMvc
        .perform(delete("/api/v1/external-access/tokens/" + tokenId).with(devUser("dev-admin")))
        .andExpect(status().isNotFound());
  }

  @Test
  void theAdministrationsListCarriesNoUsageDateAndNoValue() throws Exception {
    UUID tokenId = issueTokenId();
    tokenService.recordUse(tokenId);

    String body =
        mockMvc
            .perform(get("/api/v1/admin/external-access/tokens").with(devUser("dev-admin")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).contains(tokenId.toString());
    assertThat(body).doesNotContain("lastUsedOn");
    assertThat(body).doesNotContain("\"token\"");
    assertThat(body).doesNotContain("\"prefix\"");
  }

  @Test
  void theAdministrationsListHasNoFilterByPerson() throws Exception {
    issue();

    String withParameter =
        mockMvc
            .perform(
                get("/api/v1/admin/external-access/tokens")
                    .param("ownerUserId", administrator.getId().toString())
                    .with(devUser("dev-admin")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String plain =
        mockMvc
            .perform(get("/api/v1/admin/external-access/tokens").with(devUser("dev-admin")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(withParameter).isEqualTo(plain);
  }

  @Test
  void theAdministrationsListFiltersByExpiryHorizon() throws Exception {
    UUID soon = issueTokenId();
    jdbcTemplate.update(
        "UPDATE external_access_tokens SET expires_at = ? WHERE id = ?",
        Timestamp.from(clock.instant().plus(Duration.ofDays(2))),
        soon);
    UUID later = issueTokenId();

    String body =
        mockMvc
            .perform(
                get("/api/v1/admin/external-access/tokens")
                    .param("expiringWithinDays", "7")
                    .with(devUser("dev-admin")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).contains(soon.toString()).doesNotContain(later.toString());
  }

  @Test
  void theAdministrationBlocksOneTokenAndEveryTokenOfAPerson() throws Exception {
    UUID first = issueTokenId();
    UUID second = issueTokenId();

    mockMvc
        .perform(
            post("/api/v1/admin/external-access/tokens/" + first + "/block")
                .with(devUser("dev-admin")))
        .andExpect(status().isNoContent());
    assertThat(tokens.findById(first).orElseThrow().getRevocationReason())
        .isEqualTo(ExternalAccessTokenRevocationReason.ADMIN);

    mockMvc
        .perform(
            post("/api/v1/admin/external-access/tokens/block-by-owner")
                .with(devUser("dev-admin"))
                .content("{\"ownerUserId\":\"" + owner.getId() + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.blocked").value(1));
    assertThat(tokens.findById(second).orElseThrow().getRevokedAt()).isNotNull();
  }

  @Test
  void aRegularAccountReachesNoAdministrationEndpoint() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/external-access/tokens").with(devUser("dev-user")))
        .andExpect(status().isForbidden());
  }

  @Test
  void theEffectiveViewLosesALibraryWhenTheReadingRightGoes() throws Exception {
    UUID tokenId = issueTokenId();
    ExternalAccessTokenScopeService scope = scopeWith(ALL_RELEASED);
    assertThat(scope.effectiveLibraryIds(tokenId, owner.getOrganizationId()))
        .containsExactly(libraryId);

    grants.deleteAll(grants.findByLibraryId(libraryId));
    libraryAccess.invalidateLibrary(libraryId);

    assertThat(scope.effectiveLibraryIds(tokenId, owner.getOrganizationId())).isEmpty();
  }

  @Test
  void aWithdrawnReleaseDoesNotComeBackToLifeInAnIssuedToken() throws Exception {
    UUID tokenId = issueTokenId();

    assertThat(scopeWith(ALL_RELEASED).effectiveLibraryIds(tokenId, owner.getOrganizationId()))
        .containsExactly(libraryId);
    assertThat(scopeWith(NONE_RELEASED).effectiveLibraryIds(tokenId, owner.getOrganizationId()))
        .isEmpty();

    // The release is back; the extinguished entry of the token is not.
    assertThat(scopeWith(ALL_RELEASED).effectiveLibraryIds(tokenId, owner.getOrganizationId()))
        .isEmpty();
    assertThat(tokens.findById(tokenId).orElseThrow().getSelectedLibraryIds())
        .containsExactly(libraryId);

    // The self view shows the entry as suspended rather than dropping it (#1719): the person is
    // meant to see what a new token would have to contain again.
    mockMvc
        .perform(get("/api/v1/external-access/tokens").with(devUser("dev-user")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tokens[0].libraries[0].id").value(libraryId.toString()))
        .andExpect(jsonPath("$.tokens[0].libraries[0].suspended").value(true));
  }

  @Test
  void aLiveSelectionIsNotMarkedAsSuspended() throws Exception {
    issue();

    mockMvc
        .perform(get("/api/v1/external-access/tokens").with(devUser("dev-user")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tokens[0].libraries[0].suspended").value(false));
  }

  @Test
  void theSelfViewOfTheChannelCarriesTheSwitchAndTheCeilingAndNothingElse() throws Exception {
    setChannelEnabled(false);

    mockMvc
        .perform(get("/api/v1/external-access/settings").with(devUser("dev-user")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(false))
        .andExpect(jsonPath("$.tokenMaxLifetimeDays").value(90))
        // Quota, networks, instructions text and the last change stay with the Systemverwaltung.
        .andExpect(jsonPath("$.tokenRateLimitPerHour").doesNotExist())
        .andExpect(jsonPath("$.allowedCidrs").doesNotExist())
        .andExpect(jsonPath("$.serverInstructions").doesNotExist())
        .andExpect(jsonPath("$.updatedBy").doesNotExist());

    setChannelEnabled(true);
    mockMvc
        .perform(get("/api/v1/external-access/settings").with(devUser("dev-user")))
        .andExpect(jsonPath("$.enabled").value(true));
  }

  @Test
  void aClosedChannelEmptiesTheEffectiveViewWithoutTouchingTheToken() throws Exception {
    UUID tokenId = issueTokenId();
    setChannelEnabled(false);

    assertThat(scopeWith(ALL_RELEASED).effectiveLibraryIds(tokenId, owner.getOrganizationId()))
        .isEmpty();

    ExternalAccessToken untouched = tokens.findById(tokenId).orElseThrow();
    assertThat(untouched.getRevokedAt()).isNull();
    assertThat(untouched.getLiveLibraryIds()).containsExactly(libraryId);
  }

  @Test
  void countsTheTokensOfALibraryWithoutNamingAPerson() throws Exception {
    issue();

    assertThat(tokenService.countActiveTokensFor(libraryId)).isEqualTo(1);
    assertThat(tokenService.countActiveTokensFor(foreignLibraryId)).isZero();
  }

  @Test
  void aGrantMakesAForeignLibrarySelectable() throws Exception {
    grants.save(
        AssetGrant.forUser(
            foreignLibraryId,
            administrator.getOrganizationId(),
            owner.getId(),
            AssetRole.VIEWER,
            null,
            administrator.getId()));
    libraryAccess.invalidateLibrary(foreignLibraryId);

    mockMvc
        .perform(
            post("/api/v1/external-access/tokens")
                .with(devUser("dev-user"))
                .content(createBody(foreignLibraryId, clock.instant().plus(Duration.ofDays(10)))))
        .andExpect(status().isCreated());
  }
}
