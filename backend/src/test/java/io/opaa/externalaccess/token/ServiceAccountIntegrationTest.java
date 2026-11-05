package io.opaa.externalaccess.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.account.AccountErasureEvent;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.AccountUsability;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.CurrentUserArgumentResolver;
import io.opaa.auth.DevAuthFilter;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.externalaccess.ExternalAccessNetworkPolicy;
import io.opaa.externalaccess.ExternalAccessSettings;
import io.opaa.externalaccess.ExternalAccessSettingsService;
import io.opaa.externalaccess.security.ExternalAccessTokenAuthenticationFilter;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.library.LibraryExternalAccessService;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIntegrationTest;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The service account of ADR-0035, Entscheidung 7, end to end: its token reaches exactly the
 * libraries the library administration consented to, and each factor of that view - consent,
 * switch, token, account - takes it away on its own, on the very next call.
 *
 * <p>Every library here is also released for colleagues' tools, the second one without a consent: a
 * view that leaked through the Fremdzugangsfreigabe would show it.
 */
@OpaaIntegrationTest
class ServiceAccountIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private UserRepository users;
  @Autowired private ServiceAccountService service;
  @Autowired private ServiceAccountConsentService consentService;
  @Autowired private ServiceAccountTokenRepository serviceAccountTokens;
  @Autowired private ServiceAccountRepository accounts;
  @Autowired private ServiceAccountLibraryConsentRepository consents;
  @Autowired private ServiceAccountTokenMaintenanceStep maintenanceStep;
  @Autowired private ExternalAccessTokenCounter counter;
  @Autowired private ExternalAccessTokenLapseService personLifecycle;
  @Autowired private ExternalAccessSettingsService settings;
  @Autowired private LibraryExternalAccessService libraryRelease;
  @Autowired private KnowledgeLibraryRepository libraries;
  @Autowired private AccountUsability usability;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private ExternalAccessTokenAuthenticator authenticator;
  @Autowired private ExternalAccessTokenService tokenService;
  @Autowired private ExternalAccessNetworkPolicy networkPolicy;
  @Autowired private JsonMapper jsonMapper;
  @Autowired private ApplicationEventPublisher events;
  @Autowired private Clock clock;

  private User owner;
  private User administrator;
  private User responsible;
  private UUID consentedLibraryId;
  private UUID releasedOnlyLibraryId;
  private final List<UUID> createdAccounts = new ArrayList<>();
  private UUID accountId;
  private UUID tokenId;
  private String rawValue;

  @BeforeEach
  void setUp() throws Exception {
    mockMvc.perform(get("/api/v1/auth/me").with(devUser("dev-user"))).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/auth/me").with(devUser("dev-admin"))).andExpect(status().isOk());
    owner = users.findBySubjectAndIssuer("dev-user", "opaa-dev").orElseThrow();
    administrator = users.findBySubjectAndIssuer("dev-admin", "opaa-dev").orElseThrow();
    User person =
        new User(UUID.randomUUID().toString(), "opaa-dev", "fachverfahren@example.com", "Frau");
    person.setOrganizationId(Organization.DEFAULT_ID);
    responsible = users.save(person);
    setChannel(true, 1000, null);

    consentedLibraryId = insertLibrary("Bauakten");
    releasedOnlyLibraryId = insertLibrary("Nur freigegeben");
    release(consentedLibraryId);
    release(releasedOnlyLibraryId);

    accountId = createAccount("Bauantrag-Portal", null);
    consentService.set(
        asOwner(), consentedLibraryId, accountId, clock.instant().plus(Duration.ofDays(30)));
    ServiceAccountService.IssuedServiceAccountToken issued = issue(accountId, "Portal-Token");
    rawValue = issued.rawValue();
    tokenId = issued.token().getId();
  }

  @AfterEach
  void tearDown() {
    for (UUID account : createdAccounts) {
      jdbc.update("DELETE FROM service_accounts WHERE id = ?", account);
    }
    for (UUID library : List.of(consentedLibraryId, releasedOnlyLibraryId)) {
      jdbc.update("DELETE FROM asset_grants WHERE asset_id = ?", library);
      jdbc.update("DELETE FROM asset_visibility_history WHERE asset_id = ?", library);
      jdbc.update("DELETE FROM assets WHERE id = ?", library);
    }
    users.deleteById(responsible.getId());
  }

  @Test
  void theTokenSeesTheConsentedLibraryAndNotTheOneThatIsOnlyReleased() throws Exception {
    assertThat(searchableLibraries(rawValue)).containsExactly(consentedLibraryId.toString());
  }

  @Test
  void aWithdrawnConsentEmptiesTheViewAndKeepsItsRow() throws Exception {
    consentService.revoke(asOwner(), consentedLibraryId, accountId);

    assertThat(searchableLibraries(rawValue)).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT end_reason FROM service_account_library_consents"
                    + " WHERE service_account_id = ? AND library_id = ?",
                String.class,
                accountId,
                consentedLibraryId))
        .isEqualTo("WITHDRAWN");
  }

  @Test
  void aConsentRowOnALibraryOfAnotherOrganizationAddsNothing() throws Exception {
    UUID foreignOrganization = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO organizations (id, name) VALUES (?, ?)", foreignOrganization, "Fremdamt");
    User foreigner = new User(UUID.randomUUID().toString(), "opaa-dev", null, "Fremd");
    foreigner.setOrganizationId(foreignOrganization);
    UUID foreignOwner = users.save(foreigner).getId();
    UUID foreignLibrary = UUID.randomUUID();
    try {
      jdbc.update(
          "WITH shell AS (INSERT INTO assets (id, asset_type, organization_id, name, owner_type,"
              + " owner_user_id) VALUES (?, 'KNOWLEDGE_LIBRARY', ?, 'Fremde Bibliothek', 'USER',"
              + " ?) RETURNING id, organization_id) INSERT INTO knowledge_libraries (id,"
              + " organization_id, source_type) SELECT id, organization_id, 'UPLOAD' FROM shell",
          foreignLibrary,
          foreignOrganization,
          foreignOwner);
      consentRowByHand(foreignLibrary);

      assertThat(searchableLibraries(rawValue)).containsExactly(consentedLibraryId.toString());
    } finally {
      jdbc.update("DELETE FROM assets WHERE id = ?", foreignLibrary);
      users.deleteById(foreignOwner);
      jdbc.update("DELETE FROM organizations WHERE id = ?", foreignOrganization);
    }
  }

  @Test
  void aConsentOnAPrivateLibraryAddsNothing() throws Exception {
    UUID privateLibrary = insertLibrary("Privat", true);
    try {
      consentRowByHand(privateLibrary);

      assertThat(searchableLibraries(rawValue)).containsExactly(consentedLibraryId.toString());
    } finally {
      jdbc.update("DELETE FROM asset_grants WHERE asset_id = ?", privateLibrary);
      jdbc.update("DELETE FROM assets WHERE id = ?", privateLibrary);
    }
  }

  @Test
  void theConsentHistoryOutlivesTheLibraryAndIsEndedByTheDailyStep() throws Exception {
    UUID doomed = insertLibrary("Wird gelöscht");
    consentService.set(asOwner(), doomed, accountId, clock.instant().plus(Duration.ofDays(30)));
    jdbc.update("DELETE FROM asset_grants WHERE asset_id = ?", doomed);
    jdbc.update("DELETE FROM asset_visibility_history WHERE asset_id = ?", doomed);
    jdbc.update("DELETE FROM assets WHERE id = ?", doomed);

    maintenanceStep.run(clock.instant());

    assertThat(
            jdbc.queryForObject(
                "SELECT end_reason FROM service_account_library_consents WHERE library_id = ?",
                String.class,
                doomed))
        .isEqualTo("LIBRARY_DELETED");
    assertThat(
            service.detail(Organization.DEFAULT_ID, accountId).overview().consentedLibraryCount())
        .isEqualTo(1);
  }

  @Test
  void aConsentThatRanOutIsRecordedOnceAndStaysListed() throws Exception {
    jdbc.update(
        "UPDATE service_account_library_consents SET expires_at = ? WHERE service_account_id = ?",
        Timestamp.from(clock.instant().minus(Duration.ofMinutes(1))),
        accountId);

    maintenanceStep.run(clock.instant());
    maintenanceStep.run(clock.instant());

    assertThat(auditCount(AuditEventType.SERVICE_ACCOUNT_CONSENT_EXPIRED, consentedLibraryId))
        .isEqualTo(1);
    mockMvc
        .perform(
            get("/api/v1/libraries/" + consentedLibraryId + "/service-accounts")
                .with(devUser("dev-user")))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.consents[?(@.serviceAccountId == '" + accountId + "')].status")
                .value("EXPIRED"));
  }

  @Test
  void aConsentThatHasRunOutEmptiesTheView() throws Exception {
    jdbc.update(
        "UPDATE service_account_library_consents SET expires_at = ? WHERE service_account_id = ?",
        Timestamp.from(clock.instant().minus(Duration.ofMinutes(1))),
        accountId);

    assertThat(searchableLibraries(rawValue)).isEmpty();
  }

  @Test
  void theInstallationSwitchClosesTheChannelForTheServiceAccountToo() throws Exception {
    setChannel(false, 1000, null);

    assertRefusedWith(rawValue, "channel_closed");
  }

  @Test
  void aRevokedTokenIsRefused() throws Exception {
    service.revokeToken(
        administrator.getId(), administrator.getOrganizationId(), accountId, tokenId);

    assertRefused(rawValue);
  }

  @Test
  void anExpiredTokenIsRefused() throws Exception {
    jdbc.update(
        "UPDATE service_account_tokens SET expires_at = ? WHERE id = ?",
        Timestamp.from(clock.instant().minus(Duration.ofMinutes(1))),
        tokenId);

    assertRefused(rawValue);
  }

  @Test
  void deactivatingTheAccountRevokesEveryTokenAtOnce() throws Exception {
    String second = issue(accountId, "Zweiter").rawValue();

    service.deactivate(administrator.getId(), administrator.getOrganizationId(), accountId);

    assertRefused(rawValue);
    assertRefused(second);
    assertThat(serviceAccountTokens.findByServiceAccountIdAndRevokedAtIsNull(accountId)).isEmpty();
    assertThat(serviceAccountTokens.findById(tokenId).orElseThrow().getRevocationReason())
        .isEqualTo(ExternalAccessTokenRevocationReason.ACCOUNT_LIFECYCLE);
    assertThat(auditCount(AuditEventType.SERVICE_ACCOUNT_DEACTIVATED, accountId)).isEqualTo(1);
    assertThat(auditCount(AuditEventType.API_TOKEN_REVOKED, tokenId)).isEqualTo(1);
  }

  @Test
  void theNetworkRestrictionOfTheChannelApplies() throws Exception {
    setChannel(true, 1000, List.of("10.11.12.0/24"));

    assertRefusedWith(rawValue, "network_not_allowed");
  }

  @Test
  void aLockedResponsiblePersonLeavesTheTokensWorking() throws Exception {
    User locked = users.findById(responsible.getId()).orElseThrow();
    locked.lockFromDirectory(clock.instant());
    users.save(locked);
    // The person lifecycle ends her own tokens; it has no hold on the account's.
    personLifecycle.endTokensOfAccount(locked, administrator.getId());

    assertThat(searchableLibraries(rawValue)).containsExactly(consentedLibraryId.toString());
    assertThat(
            service
                .detail(administrator.getOrganizationId(), accountId)
                .overview()
                .responsiblePersonMissing())
        .isTrue();
  }

  @Test
  void aDeletedResponsiblePersonLeavesTheTokensWorking() throws Exception {
    users.deleteById(responsible.getId());

    assertThat(searchableLibraries(rawValue)).containsExactly(consentedLibraryId.toString());
    assertThat(accounts.findById(accountId).orElseThrow().getResponsibleUserId()).isNull();
    mockMvc
        .perform(get("/api/v1/admin/service-accounts/" + accountId).with(devUser("dev-admin")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.serviceAccount.responsiblePersonMissing").value(true));
  }

  @Test
  void anErasedResponsiblePersonLeavesOnlyTheReferenceBehind() throws Exception {
    // Published in a transaction, as AccountErasureService does: every listener joins it.
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status ->
                events.publishEvent(
                    new AccountErasureEvent(
                        asAdministrator(),
                        users.findById(responsible.getId()).orElseThrow(),
                        clock.instant())));

    assertThat(accounts.findById(accountId).orElseThrow().getResponsibleUserId()).isNull();
    assertThat(searchableLibraries(rawValue)).containsExactly(consentedLibraryId.toString());
  }

  @Test
  void theTokensOfOneAccountShareItsHourlyQuota() throws Exception {
    UUID limited = createAccount("Gedrosseltes Verfahren", 2);
    consentService.set(
        asOwner(), consentedLibraryId, limited, clock.instant().plus(Duration.ofDays(30)));
    String first = issue(limited, "Erster").rawValue();
    String second = issue(limited, "Zweiter").rawValue();

    searchableLibraries(first);
    searchableLibraries(second);

    mockMvc
        .perform(get("/api/v1/search/libraries").with(bearer(first)))
        .andExpect(status().isTooManyRequests());
  }

  @Test
  void theCallerOfAServiceAccountTokenIsTheAccountAndNoPerson() throws Exception {
    CurrentUser caller = callerAuthenticatedBy(rawValue);

    assertThat(caller.id()).isEqualTo(accountId);
    assertThat(caller.organizationId()).isEqualTo(Organization.DEFAULT_ID);
    assertThat(caller.systemRole()).isEqualTo(SystemRole.USER);
    assertThat(caller.accessTokenId()).isEqualTo(tokenId);
    assertThat(users.findById(caller.id())).isEmpty();
  }

  @Test
  void theAdministrationCreatesChangesAndDeactivatesAnAccount() throws Exception {
    String created =
        mockMvc
            .perform(
                post("/api/v1/admin/service-accounts")
                    .with(devUser("dev-admin"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"name\":\"Fachverfahren X\",\"responsibleUserId\":\""
                            + responsible.getId()
                            + "\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.active").value(true))
            .andExpect(jsonPath("$.responsiblePersonMissing").value(false))
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID id = UUID.fromString(JsonPath.read(created, "$.id"));
    createdAccounts.add(id);

    mockMvc
        .perform(
            put("/api/v1/admin/service-accounts/" + id)
                .with(devUser("dev-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"Fachverfahren Y\",\"responsibleUserId\":\""
                        + responsible.getId()
                        + "\",\"rateLimitPerHour\":5}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Fachverfahren Y"))
        .andExpect(jsonPath("$.effectiveRateLimitPerHour").value(5));
    mockMvc
        .perform(get("/api/v1/admin/service-accounts").with(devUser("dev-admin")))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.serviceAccounts[?(@.id == '" + id + "')].name").value("Fachverfahren Y"));
    mockMvc
        .perform(
            post("/api/v1/admin/service-accounts/" + id + "/deactivate").with(devUser("dev-admin")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(false));

    assertThat(auditCount(AuditEventType.SERVICE_ACCOUNT_CREATED, id)).isEqualTo(1);
    assertThat(auditCount(AuditEventType.SERVICE_ACCOUNT_CHANGED, id)).isEqualTo(1);
    assertThat(auditCount(AuditEventType.SERVICE_ACCOUNT_DEACTIVATED, id)).isEqualTo(1);
  }

  @Test
  void aPersonWithoutTheSystemRoleCannotReachTheAdministration() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/service-accounts").with(devUser("dev-user")))
        .andExpect(status().isForbidden());
  }

  @Test
  void theLibraryAdministrationsConsentIsHistorisedAndAudited() throws Exception {
    UUID other = createAccount("Archivschnittstelle", null);
    String path = "/api/v1/libraries/" + consentedLibraryId + "/service-accounts/" + other;
    Instant first = clock.instant().plus(Duration.ofDays(10)).truncatedTo(ChronoUnit.SECONDS);
    Instant later = first.plus(Duration.ofDays(5));

    mockMvc
        .perform(
            put(path)
                .with(devUser("dev-user"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expiresAt\":\"" + first + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"));
    mockMvc
        .perform(
            put(path)
                .with(devUser("dev-user"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expiresAt\":\"" + later + "\"}"))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            get("/api/v1/libraries/" + consentedLibraryId + "/service-accounts")
                .with(devUser("dev-user")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.consents[?(@.serviceAccountId == '" + other + "')]").exists());
    mockMvc.perform(delete(path).with(devUser("dev-user"))).andExpect(status().isNoContent());

    assertThat(
            jdbc.queryForList(
                "SELECT end_reason FROM service_account_library_consents"
                    + " WHERE service_account_id = ? ORDER BY granted_at, ended_at",
                String.class,
                other))
        .containsExactlyInAnyOrder("REPLACED", "WITHDRAWN");
    assertThat(auditCount(AuditEventType.SERVICE_ACCOUNT_CONSENT_GRANTED, consentedLibraryId))
        .isGreaterThanOrEqualTo(1);
    assertThat(auditCount(AuditEventType.SERVICE_ACCOUNT_CONSENT_CHANGED, consentedLibraryId))
        .isEqualTo(1);
    assertThat(auditCount(AuditEventType.SERVICE_ACCOUNT_CONSENT_REVOKED, consentedLibraryId))
        .isEqualTo(1);
  }

  @Test
  void aConsentReachesNoFurtherThanMaxReleaseDays() throws Exception {
    Instant tooFar = clock.instant().plus(Duration.ofDays(consentService.maxConsentDays() + 1L));

    mockMvc
        .perform(
            put("/api/v1/libraries/" + releasedOnlyLibraryId + "/service-accounts/" + accountId)
                .with(devUser("dev-user"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expiresAt\":\"" + tooFar + "\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void theTokenCountAndTheAdminListIncludeTheServiceAccountsToken() throws Exception {
    assertThat(counter.countActiveTokensFor(consentedLibraryId)).isEqualTo(1);
    assertThat(counter.countActiveTokensFor(releasedOnlyLibraryId)).isZero();

    mockMvc
        .perform(get("/api/v1/admin/external-access/tokens").with(devUser("dev-admin")))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.tokens[?(@.id == '" + tokenId + "')].ownerKind").value("SERVICE_ACCOUNT"))
        .andExpect(
            jsonPath("$.tokens[?(@.id == '" + tokenId + "')].ownerUserId")
                .value(accountId.toString()));
  }

  @Test
  void theRemindersGoToTheResponsiblePersonAndTheAdministrationOnce() {
    ZoneId zone = clock.getZone();
    Instant now = clock.instant();
    jdbc.update(
        "UPDATE service_account_tokens SET expires_at = ? WHERE id = ?",
        Timestamp.from(noonOn(now, 3, zone)),
        tokenId);
    jdbc.update(
        "UPDATE service_account_library_consents SET expires_at = ? WHERE service_account_id = ?",
        Timestamp.from(now.plus(Duration.ofDays(1))),
        accountId);
    RecordingMailer mailer = new RecordingMailer();
    ServiceAccountReminderStep step = reminderStep(mailer, 7);

    step.run(now);
    step.run(now);

    assertThat(mailer.tokenRecipients).contains(responsible.getId(), administrator.getId());
    assertThat(mailer.tokenRecipients).doesNotHaveDuplicates();
    assertThat(mailer.consentRecipients).contains(responsible.getId(), administrator.getId());
    assertThat(mailer.consentRecipients).doesNotHaveDuplicates();
  }

  @Test
  void withoutAResponsiblePersonOnlyTheAdministrationIsReminded() {
    User locked = users.findById(responsible.getId()).orElseThrow();
    locked.lockFromDirectory(clock.instant());
    users.save(locked);
    ZoneId zone = clock.getZone();
    Instant now = clock.instant();
    jdbc.update(
        "UPDATE service_account_tokens SET expires_at = ? WHERE id = ?",
        Timestamp.from(noonOn(now, 14, zone)),
        tokenId);
    RecordingMailer mailer = new RecordingMailer();

    reminderStep(mailer, 0).run(now);

    assertThat(mailer.tokenRecipients)
        .contains(administrator.getId())
        .doesNotContain(responsible.getId());
    assertThat(mailer.consentRecipients).isEmpty();
  }

  @Test
  void theLapseOfATokenIsRecordedOnce() {
    jdbc.update(
        "UPDATE service_account_tokens SET expires_at = ? WHERE id = ?",
        Timestamp.from(clock.instant().minus(Duration.ofMinutes(1))),
        tokenId);

    maintenanceStep.run(clock.instant());
    maintenanceStep.run(clock.instant());

    assertThat(serviceAccountTokens.findById(tokenId).orElseThrow().getLapseRecordedAt())
        .isNotNull();
    assertThat(auditCount(AuditEventType.API_TOKEN_EXPIRED, tokenId)).isEqualTo(1);
  }

  private ServiceAccountReminderStep reminderStep(RecordingMailer mailer, int consentLeadDays) {
    return new ServiceAccountReminderStep(
        serviceAccountTokens,
        consents,
        accounts,
        libraries,
        users,
        usability,
        mailer,
        settings,
        () -> consentLeadDays,
        transactionManager,
        clock);
  }

  /** Records whom a reminder went to, without an SMTP server in the way. */
  private static final class RecordingMailer extends ServiceAccountMailer {

    private final List<UUID> tokenRecipients = new ArrayList<>();
    private final List<UUID> consentRecipients = new ArrayList<>();

    private RecordingMailer() {
      super(null, Clock.systemDefaultZone());
    }

    @Override
    public void sendTokenExpiring(
        User recipient, String serviceAccountName, String tokenName, Instant expiresAt) {
      tokenRecipients.add(recipient.getId());
    }

    @Override
    public void sendConsentExpiring(
        User recipient, String serviceAccountName, String libraryName, Instant expiresAt) {
      consentRecipients.add(recipient.getId());
    }
  }

  /** A consent row written past the service, as a stale or tampered row would stand. */
  private void consentRowByHand(UUID libraryId) {
    jdbc.update(
        "INSERT INTO service_account_library_consents (id, service_account_id, library_id,"
            + " granted_at, expires_at) VALUES (?, ?, ?, now(), ?)",
        UUID.randomUUID(),
        accountId,
        libraryId,
        Timestamp.from(clock.instant().plus(Duration.ofDays(10))));
  }

  private UUID createAccount(String name, Integer rateLimitPerHour) {
    UUID id =
        service
            .create(
                administrator.getId(),
                Organization.DEFAULT_ID,
                name,
                null,
                responsible.getId(),
                rateLimitPerHour)
            .account()
            .getId();
    createdAccounts.add(id);
    return id;
  }

  private ServiceAccountService.IssuedServiceAccountToken issue(UUID account, String name) {
    return service.issueToken(
        administrator.getId(),
        Organization.DEFAULT_ID,
        account,
        name,
        clock.instant().plus(Duration.ofDays(30)));
  }

  private CurrentUser asAdministrator() {
    return CurrentUser.of(
        administrator.getId(),
        administrator.getOrganizationId(),
        administrator.getSystemRole(),
        "Systemverwaltung");
  }

  private CurrentUser asOwner() {
    return CurrentUser.of(
        owner.getId(), owner.getOrganizationId(), owner.getSystemRole(), "Verantwortliche");
  }

  private List<String> searchableLibraries(String value) throws Exception {
    String body =
        mockMvc
            .perform(get("/api/v1/search/libraries").with(bearer(value)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$[*].id");
  }

  private void assertRefused(String value) throws Exception {
    mockMvc
        .perform(get("/api/v1/search/libraries").with(bearer(value)))
        .andExpect(status().isUnauthorized());
  }

  private void assertRefusedWith(String value, String marker) throws Exception {
    mockMvc
        .perform(get("/api/v1/search/libraries").with(bearer(value)))
        .andExpect(status().isUnauthorized())
        .andExpect(
            result ->
                assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE))
                    .contains("error_description=\"" + marker + "\""));
  }

  private long auditCount(AuditEventType type, UUID objectId) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM audit_log WHERE event_type = ? AND object_id = ?",
        Long.class,
        type.name(),
        objectId.toString());
  }

  /** Runs the channel's real access-token filter and returns the caller it binds. */
  private CurrentUser callerAuthenticatedBy(String value) throws Exception {
    ExternalAccessTokenAuthenticationFilter filter =
        new ExternalAccessTokenAuthenticationFilter(
            authenticator, tokenService, networkPolicy, List.of(), jsonMapper, clock);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/search/libraries");
    request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + value);
    AtomicReference<CurrentUser> bound = new AtomicReference<>();
    filter.doFilter(
        request,
        new MockHttpServletResponse(),
        (chainRequest, chainResponse) ->
            bound.set(CurrentUserArgumentResolver.callerOf((HttpServletRequest) chainRequest)));
    assertThat(bound.get()).isNotNull();
    return bound.get();
  }

  /** The channel's settings; {@code cidrs} {@code null} keeps the seeded networks. */
  private void setChannel(boolean enabled, int quotaPerHour, List<String> cidrs) {
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
            quotaPerHour,
            cidrs == null ? values.allowedCidrs() : cidrs,
            values.massRetrievalAlertThreshold(),
            values.serverInstructions(),
            values.releaseReminderLeadDays()));
  }

  private void release(UUID library) {
    libraryRelease.setExternalAccess(
        asOwner(), library, true, clock.instant().plus(Duration.ofDays(60)));
  }

  private UUID insertLibrary(String name) {
    return insertLibrary(name, false);
  }

  private UUID insertLibrary(String name, boolean ownerOnly) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "WITH shell AS (INSERT INTO assets (id, asset_type, organization_id, name, owner_type,"
            + " owner_user_id, owner_only) VALUES (?, 'KNOWLEDGE_LIBRARY', ?, ?, 'USER', ?, ?)"
            + " RETURNING id, organization_id) INSERT INTO knowledge_libraries (id,"
            + " organization_id, source_type) SELECT id, organization_id, 'UPLOAD' FROM shell",
        id,
        Organization.DEFAULT_ID,
        name,
        owner.getId(),
        ownerOnly);
    jdbc.update(
        "INSERT INTO asset_grants (id, asset_type, asset_id, organization_id, subject_type,"
            + " subject_user_id, role, created_at, updated_at) VALUES (?, 'KNOWLEDGE_LIBRARY', ?,"
            + " ?, 'USER', ?, 'OWNER', now(), now())",
        UUID.randomUUID(),
        id,
        Organization.DEFAULT_ID,
        owner.getId());
    return id;
  }

  private static Instant noonOn(Instant now, int inDays, ZoneId zone) {
    return LocalDate.ofInstant(now, zone)
        .plusDays(inDays)
        .atStartOfDay(zone)
        .plusHours(12)
        .toInstant()
        .truncatedTo(ChronoUnit.SECONDS);
  }

  private RequestPostProcessor devUser(String subject) {
    return request -> {
      request.addHeader(DevAuthFilter.DEV_USER_HEADER, subject);
      return request;
    };
  }

  private RequestPostProcessor bearer(String value) {
    return request -> {
      request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + value);
      return request;
    };
  }
}
