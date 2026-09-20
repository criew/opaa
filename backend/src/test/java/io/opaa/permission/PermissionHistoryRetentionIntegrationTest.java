package io.opaa.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ValidationException;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.OpaaIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The governance setting itself against the real, Liquibase-built schema (#1833, ADR-0036
 * Entscheidung 8): the delivered value, the bounds the database refuses to leave, who may change
 * the period, and that a change is a governance event.
 *
 * <p>{@code SeededRowRestorer} puts the settings row back after every method, so a method may
 * change the period without arranging that itself.
 */
@OpaaIntegrationTest
class PermissionHistoryRetentionIntegrationTest {

  @Autowired private PermissionHistoryRetentionService retentionService;
  @Autowired private PermissionHistoryRetentionSettingsRepository repository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationId;
  private CurrentUser admin;
  private CurrentUser ordinaryUser;

  @BeforeEach
  void setUp() {
    organizationId =
        organizationRepository
            .save(new Organization(UUID.randomUUID(), "Permission History Retention Org"))
            .getId();
    admin =
        CurrentUser.of(
            persistUser("admin"), organizationId, SystemRole.SYSTEM_ADMIN, "Systemverwaltung");
    ordinaryUser =
        CurrentUser.of(persistUser("user"), organizationId, SystemRole.USER, "Sachbearbeitung");
  }

  @AfterEach
  void tearDown() {
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    jdbcTemplate.update("DELETE FROM users WHERE organization_id = ?", organizationId);
    organizationRepository.deleteById(organizationId);
  }

  @Test
  void aFreshInstallationKeepsTheRightsHistoryForThreeYears() {
    PermissionHistoryRetentionSettings settings = retentionService.read(admin);

    assertThat(settings.getRetentionMonths())
        .isEqualTo(PermissionHistoryRetentionSettings.DEFAULT_RETENTION_MONTHS)
        .isEqualTo(36);
    assertThat(settings.getLastCutoff())
        .as("the deletion starts where the installation does, not at the epoch")
        .isNotNull();
    assertThat(settings.getUpdatedAt()).isNotNull();
  }

  @Test
  void theSystemAdministrationChangesThePeriodAndTheChangeIsAGovernanceEvent() {
    PermissionHistoryRetentionSettings updated = retentionService.updateRetentionMonths(admin, 60);

    assertThat(updated.getRetentionMonths())
        .as("the response carries the new value, not the one it replaced")
        .isEqualTo(60);
    assertThat(repository.findSingleton().orElseThrow().getRetentionMonths()).isEqualTo(60);

    List<Map<String, Object>> events = governanceEvents();
    assertThat(events).hasSize(1);
    Map<String, Object> event = events.get(0);
    assertThat(event.get("object_type")).isEqualTo(AuditObjectType.SYSTEM_SETTING.name());
    assertThat((String) event.get("before")).contains("36");
    assertThat((String) event.get("after")).contains("60");
  }

  @Test
  void thePeriodCannotLeaveTheBoundsThroughTheApi() {
    assertThatThrownBy(() -> retentionService.updateRetentionMonths(admin, 11))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("12")
        .hasMessageContaining("120");
    assertThatThrownBy(() -> retentionService.updateRetentionMonths(admin, 121))
        .isInstanceOf(ValidationException.class);

    assertThat(repository.findSingleton().orElseThrow().getRetentionMonths()).isEqualTo(36);
    assertThat(governanceEvents()).as("a refused change is no change").isEmpty();
  }

  /**
   * The bound is the database's, not only the service's: the acceptance criterion asks for a value
   * outside 12..120 to be impossible "weder über die API noch direkt in der Datenbank".
   */
  @Test
  void thePeriodCannotLeaveTheBoundsThroughTheDatabaseEither() {
    assertThatThrownBy(() -> jdbcTemplate.update(updateMonths(11)))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("chk_permission_history_retention_months");
    assertThatThrownBy(() -> jdbcTemplate.update(updateMonths(121)))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("chk_permission_history_retention_months");
  }

  @Test
  void aSecondSettingsRowIsImpossible() {
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "INSERT INTO permission_history_retention_settings"
                        + " (id, retention_months, updated_at) VALUES (2, 36, now())"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("chk_permission_history_retention_singleton");
  }

  @Test
  void onlyTheSystemAdministrationSeesAndChangesThePeriod() {
    assertThatThrownBy(() -> retentionService.read(ordinaryUser))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> retentionService.updateRetentionMonths(ordinaryUser, 60))
        .isInstanceOf(AccessDeniedException.class);

    assertThat(repository.findSingleton().orElseThrow().getRetentionMonths()).isEqualTo(36);
  }

  private static String updateMonths(int months) {
    return "UPDATE permission_history_retention_settings SET retention_months = "
        + months
        + " WHERE id = 1";
  }

  /**
   * Only this class's own organization - the shared database holds every other class's rows. Read
   * over JDBC because {@code AuditLogRepository} is package-private to {@code io.opaa.audit}.
   */
  private List<Map<String, Object>> governanceEvents() {
    return jdbcTemplate.queryForList(
        "SELECT object_type, \"before\", \"after\" FROM audit_log"
            + " WHERE organization_id = ? AND event_type = ?",
        organizationId,
        AuditEventType.PERMISSION_HISTORY_RETENTION_CHANGED.name());
  }

  private UUID persistUser(String name) {
    User user =
        new User(
            "permission-history-retention-" + UUID.randomUUID(),
            "https://issuer.example",
            UUID.randomUUID() + "@example.com",
            name);
    user.setOrganizationId(organizationId);
    return userRepository.save(user).getId();
  }
}
