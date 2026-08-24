package io.opaa.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.OpaaIntegrationTest;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * #395 acceptance criteria: "Eine Protokollfrist kürzer als die Inhaltsaufbewahrung erzeugt eine
 * Warnung". A separate Spring context from {@link AuditRetentionSettingsServiceIntegrationTest}
 * because it registers a {@link ContentRetentionProvider} bean - {@link ContentRetentionProvider}'s
 * own Javadoc explains why no such bean exists in production yet (#216 is later, separate scope):
 * this test proves the wiring works the moment one is registered, without requiring #216 to exist
 * first.
 */
// Own @Import (below) registers a ContentRetentionProvider not present in production yet -
// documented exception per AGENTS.md.
@OpaaIntegrationTest
@Import(AuditRetentionContentWarningIntegrationTest.FortyEightMonthContentRetentionConfig.class)
class AuditRetentionContentWarningIntegrationTest {

  @Autowired private AuditRetentionSettingsService retentionSettingsService;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationId;
  private UUID userId;

  @BeforeEach
  void setUp() {
    organizationId =
        organizationRepository
            .save(new Organization(UUID.randomUUID(), "Retention Warning Test Org"))
            .getId();
    User user = new User(UUID.randomUUID().toString(), "test-issuer", "warn@example.com", "Test");
    user.setOrganizationId(organizationId);
    userId = userRepository.save(user).getId();
  }

  @AfterEach
  void tearDown() {
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    jdbcTemplate.update(
        "UPDATE audit_retention_settings SET retention_months = 36, updated_at = now()"
            + " WHERE id = 1");
    userRepository.deleteById(userId);
    organizationRepository.deleteById(organizationId);
  }

  @Test
  void aRetentionShorterThanContentRetentionWarns() {
    AuditRetentionUpdateResult result =
        retentionSettingsService.updateRetention(organizationId, userId, 24, null);

    assertThat(result.inconsistentWithContentRetention()).isTrue();
  }

  @Test
  void aRetentionAtLeastAsLongAsContentRetentionDoesNotWarn() {
    AuditRetentionUpdateResult result =
        retentionSettingsService.updateRetention(organizationId, userId, 60, null);

    assertThat(result.inconsistentWithContentRetention()).isFalse();
  }

  @TestConfiguration
  static class FortyEightMonthContentRetentionConfig {

    @Bean
    @Primary
    ContentRetentionProvider fortyEightMonthContentRetention() {
      return () -> Optional.of(48);
    }
  }
}
