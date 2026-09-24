package io.opaa.externalaccess.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.api.types.AssetOwnerType;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.audit.AuditRetentionSettingsService;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.DevAuthFilter;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.externalaccess.ExternalAccessSettings;
import io.opaa.externalaccess.ExternalAccessSettingsService;
import io.opaa.library.KnowledgeLibraryService;
import io.opaa.library.LibraryCreation;
import io.opaa.mail.SendResult;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The three daily steps of the access tokens (#1718): the reminder before an expiry, the
 * Ausserkrafttreten with its one audit entry, and the Loeschfrist coupled to the audit retention.
 *
 * <p>The retention assertion runs in both directions on purpose - a row inside the frist stays, one
 * outside it goes. A test that only checked the deletion would pass just as happily against the
 * blanket "delete expired rows the day after" the specification expressly rules out.
 */
@OpaaIntegrationTest
class ExternalAccessTokenLifecycleIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private OwnLibraryFixtures ownLibraryFixtures;
  @Autowired private UserRepository users;
  @Autowired private KnowledgeLibraryService libraryService;
  @Autowired private ExternalAccessTokenRepository tokens;
  @Autowired private ExternalAccessTokenLapseStep lapseStep;
  @Autowired private ExternalAccessTokenRetentionStep retentionStep;
  @Autowired private AuditEventRecorder audit;
  @Autowired private AuditRetentionSettingsService retention;
  @Autowired private ExternalAccessSettingsService settings;
  @Autowired private Clock clock;

  private User owner;
  private User administrator;
  private UUID libraryId;

  @BeforeEach
  void setUp() throws Exception {
    mockMvc.perform(get("/api/v1/auth/me").with(devUser())).andExpect(status().isOk());
    owner = users.findBySubjectAndIssuer("dev-user", "opaa-dev").orElseThrow();
    mockMvc.perform(get("/api/v1/auth/me").with(devUser("dev-admin"))).andExpect(status().isOk());
    administrator = users.findBySubjectAndIssuer("dev-admin", "opaa-dev").orElseThrow();
    setChannelEnabled(true);
    libraryId = createLibrary();
    removeOwnTokens();
  }

  /**
   * The installation switch of #1717. The settings row is installation-wide, so {@code
   * SeededRowRestorer} puts it back after every method.
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
    removeOwnTokens();
    ownLibraryFixtures.removeLibraries(libraryId);
  }

  private void removeOwnTokens() {
    jdbcTemplate.update("DELETE FROM external_access_tokens WHERE user_id = ?", owner.getId());
  }

  private RequestPostProcessor devUser() {
    return devUser("dev-user");
  }

  private RequestPostProcessor devUser(String subject) {
    return request -> {
      request.addHeader(DevAuthFilter.DEV_USER_HEADER, subject);
      request.setContentType(MediaType.APPLICATION_JSON_VALUE);
      return request;
    };
  }

  private UUID createLibrary() {
    return libraryService
        .createLibrary(
            new LibraryCreation(
                "Lebenszyklus-Bibliothek",
                null,
                AssetOwnerType.USER,
                owner.getId(),
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
            CurrentUser.of(owner.getId(), owner.getOrganizationId(), owner.getSystemRole(), "x"))
        .library()
        .getId();
  }

  private ExternalAccessToken store(String name, Instant expiresAt) {
    return tokens.save(
        new ExternalAccessToken(
            owner.getId(),
            name,
            ExternalAccessTokenValues.VALUE_PREFIX + "abcdef",
            UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", ""),
            clock.instant(),
            expiresAt,
            List.of(libraryId)));
  }

  @Test
  void theLapseStepWritesExactlyOneEntryAndClearsTheDayOfUse() {
    ExternalAccessToken token = store("Abgelaufen", clock.instant().minus(Duration.ofMinutes(1)));
    token.touch(LocalDate.now());
    tokens.save(token);

    lapseStep.run(clock.instant());
    lapseStep.run(clock.instant());

    ExternalAccessToken lapsed = tokens.findById(token.getId()).orElseThrow();
    assertThat(lapsed.getRevocationReason()).isEqualTo(ExternalAccessTokenRevocationReason.EXPIRED);
    assertThat(lapsed.getLastUsedOn()).isNull();
    assertThat(lapsed.getLapseRecordedAt()).isNotNull();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE event_type = ? AND object_id = ?",
                Long.class,
                AuditEventType.API_TOKEN_EXPIRED.name(),
                token.getId().toString()))
        .isEqualTo(1);
  }

  @Test
  void aFailedEntryLeavesTheTokenPendingForTheNextRun() {
    ExternalAccessToken stubborn = store("Scheitert", clock.instant().minus(Duration.ofMinutes(1)));
    ExternalAccessToken neighbour = store("Daneben", clock.instant().minus(Duration.ofMinutes(1)));
    ExternalAccessTokenLapseStep failingStep =
        new ExternalAccessTokenLapseStep(
            tokens,
            new ExternalAccessTokenLapseService(tokens, users, audit, clock) {
              @Override
              public boolean recordLapse(UUID tokenId, Instant now) {
                if (tokenId.equals(stubborn.getId())) {
                  throw new IllegalStateException("the audit write failed");
                }
                return super.recordLapse(tokenId, now);
              }
            });

    failingStep.run(clock.instant());

    // No marker, so findLapsed offers it again - a lost entry must never become unreachable.
    assertThat(tokens.findById(stubborn.getId()).orElseThrow().getLapseRecordedAt()).isNull();
    assertThat(tokens.findLapsed(clock.instant()))
        .extracting(ExternalAccessToken::getId)
        .contains(stubborn.getId());
    // ... and one failing token does not stop the run.
    assertThat(tokens.findById(neighbour.getId()).orElseThrow().getLapseRecordedAt()).isNotNull();
  }

  @Test
  void theMarkerAndTheEntryShareOneTransaction() throws Exception {
    Transactional annotation =
        ExternalAccessTokenLapseService.class
            .getMethod("recordLapse", UUID.class, Instant.class)
            .getAnnotation(Transactional.class);

    // The marker is what keeps the entry to exactly one per token - and therefore also what would
    // hide a lost entry forever. Both writes must commit together, per token.
    assertThat(annotation).isNotNull();
    assertThat(annotation.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
  }

  @Test
  void aRevokedTokenGetsItsMarkerWithoutASecondEvent() {
    ExternalAccessToken token = store("Widerrufen", clock.instant().plus(Duration.ofDays(30)));
    token.revoke(ExternalAccessTokenRevocationReason.OWNER, clock.instant());
    tokens.save(token);

    lapseStep.run(clock.instant());

    assertThat(tokens.findById(token.getId()).orElseThrow().getLapseRecordedAt()).isNotNull();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE event_type = ? AND object_id = ?",
                Long.class,
                AuditEventType.API_TOKEN_EXPIRED.name(),
                token.getId().toString()))
        .isZero();
  }

  @Test
  void theRetentionFristKeepsARowInsideItAndRemovesOneOutsideIt() {
    int months = retention.currentRetentionMonths();
    ExternalAccessToken inside = store("Innerhalb", clock.instant().minus(Duration.ofDays(1)));
    ExternalAccessToken outside = store("Ausserhalb", clock.instant().minus(Duration.ofDays(1)));
    lapseStep.run(clock.instant());
    // The frist is measured from the moment the token stopped working, not from its expiry.
    jdbcTemplate.update(
        "UPDATE external_access_tokens SET lapse_recorded_at = ? WHERE id = ?",
        Timestamp.from(clock.instant().minus(Duration.ofDays(32L * months + 30))),
        outside.getId());

    retentionStep.run(clock.instant());

    assertThat(tokens.findById(inside.getId())).isPresent();
    assertThat(tokens.findById(outside.getId())).isEmpty();
  }

  @Test
  void aStillLiveTokenIsNeverRemovedByTheRetentionStep() {
    ExternalAccessToken live = store("Lebendig", clock.instant().plus(Duration.ofDays(30)));

    retentionStep.run(clock.instant());

    assertThat(tokens.findById(live.getId())).isPresent();
  }

  @Test
  void theReminderGoesOutFourteenAndThreeDaysBeforeTheExpiry() {
    ZoneId zone = ZoneId.systemDefault();
    Instant now = clock.instant();
    ExternalAccessToken inFourteen = store("In vierzehn Tagen", noonOn(now, 14, zone));
    ExternalAccessToken inThree = store("In drei Tagen", noonOn(now, 3, zone));
    ExternalAccessToken inSeven = store("In sieben Tagen", noonOn(now, 7, zone));
    RecordingMailer recording = new RecordingMailer();
    ExternalAccessTokenExpiryReminderStep step =
        new ExternalAccessTokenExpiryReminderStep(tokens, users, recording, settings, zone);

    step.run(now);

    assertThat(recording.reminded)
        .containsExactlyInAnyOrder(inFourteen.getName(), inThree.getName())
        .doesNotContain(inSeven.getName());
  }

  @Test
  void aClosedChannelSendsNoReminderAtAll() {
    ZoneId zone = ZoneId.systemDefault();
    Instant now = clock.instant();
    store("In drei Tagen", noonOn(now, 3, zone));
    setChannelEnabled(false);
    RecordingMailer recording = new RecordingMailer();

    new ExternalAccessTokenExpiryReminderStep(tokens, users, recording, settings, zone).run(now);

    assertThat(recording.reminded).isEmpty();
  }

  /** Records which tokens a reminder went out for, without an SMTP server in the way. */
  private static final class RecordingMailer extends ExternalAccessTokenMailer {

    private final List<String> reminded = new java.util.ArrayList<>();

    private RecordingMailer() {
      super(null, ZoneId.systemDefault());
    }

    @Override
    public SendResult sendExpiring(User owner, String tokenName, Instant expiresAt) {
      reminded.add(tokenName);
      return new SendResult.Sent(owner.getEmail());
    }
  }

  private static Instant noonOn(Instant now, int inDays, ZoneId zone) {
    return LocalDate.ofInstant(now, zone)
        .plusDays(inDays)
        .atStartOfDay(zone)
        .plusHours(12)
        .toInstant()
        .truncatedTo(ChronoUnit.SECONDS);
  }
}
