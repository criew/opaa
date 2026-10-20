package io.opaa.externalaccess.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.api.types.AssetOwnerType;
import io.opaa.api.types.AuditEventType;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.DevAuthFilter;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.knowledge.SourceType;
import io.opaa.library.KnowledgeLibraryService;
import io.opaa.library.LibraryCreation;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import io.opaa.test.TwoInstanceRace;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Two instances run the daily steps of the access tokens at the same moment (ADR-0053, Entscheidung
 * 7): the scheduled-run lock keeps that from happening, but a run outliving its lock must not
 * double what it records. Each token's {@code API_TOKEN_EXPIRED} stands in the audit exactly once.
 */
@OpaaIntegrationTest
class ExternalAccessTokenTwoInstanceIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private OwnLibraryFixtures ownLibraryFixtures;
  @Autowired private UserRepository users;
  @Autowired private KnowledgeLibraryService libraryService;
  @Autowired private ExternalAccessTokenRepository tokens;
  @Autowired private ExternalAccessTokenLapseService lapses;
  @Autowired private Clock clock;

  private User owner;
  private UUID libraryId;

  @BeforeEach
  void setUp() throws Exception {
    mockMvc
        .perform(get("/api/v1/auth/me").header(DevAuthFilter.DEV_USER_HEADER, "dev-user"))
        .andExpect(status().isOk());
    owner = users.findBySubjectAndIssuer("dev-user", "opaa-dev").orElseThrow();
    libraryId =
        libraryService
            .createLibrary(
                new LibraryCreation(
                    "Zwei Instanzen",
                    null,
                    AssetOwnerType.USER,
                    owner.getId(),
                    SourceType.UPLOAD,
                    null,
                    null,
                    null,
                    null,
                    null),
                CurrentUser.of(
                    owner.getId(), owner.getOrganizationId(), owner.getSystemRole(), "x"))
            .library()
            .getId();
    removeOwnTokens();
  }

  @AfterEach
  void tearDown() {
    removeOwnTokens();
    ownLibraryFixtures.removeLibraries(libraryId);
  }

  private void removeOwnTokens() {
    jdbcTemplate.update("DELETE FROM external_access_tokens WHERE user_id = ?", owner.getId());
  }

  @Test
  void twoInstancesRecordTheLapseOfATokenOnce() throws Exception {
    ExternalAccessToken token = store(clock.instant().minus(Duration.ofMinutes(1)));
    Instant now = clock.instant();

    new TwoInstanceRace(dataSource)
        .run(
            () -> lapses.recordLapse(token.getId(), now),
            () -> lapses.recordLapse(token.getId(), now),
            "SELECT 1 FROM external_access_tokens WHERE id = ? FOR UPDATE",
            token.getId());

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE event_type = ? AND object_id = ?",
                Long.class,
                AuditEventType.API_TOKEN_EXPIRED.name(),
                token.getId().toString()))
        .isEqualTo(1);
    assertThat(tokens.findById(token.getId()).orElseThrow().getLapseRecordedAt()).isNotNull();
  }

  private ExternalAccessToken store(Instant expiresAt) {
    return tokens.save(
        new ExternalAccessToken(
            owner.getId(),
            "Zwei Instanzen",
            ExternalAccessTokenValues.VALUE_PREFIX + "abcdef",
            UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", ""),
            clock.instant(),
            expiresAt,
            List.of(libraryId)));
  }
}
