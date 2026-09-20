package io.opaa.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.DevAuthFilter;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

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
  @Autowired private MockMvc mockMvc;

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

  /**
   * The HTTP methods below act as the dev users of the default organization, so their governance
   * events are not covered by the organization-scoped delete. No other class writes this event type
   * - the second delete is this class's own footprint, not a sweep over foreign rows.
   */
  @AfterEach
  void tearDown() {
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    jdbcTemplate.update(
        "DELETE FROM audit_log WHERE event_type = ?",
        AuditEventType.PERMISSION_HISTORY_RETENTION_CHANGED.name());
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

  @Test
  void theEndpointAnswersTheSystemAdministrationWithTheCurrentSetting() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/permission-history/retention").with(devUser("dev-admin")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.retentionMonths").value(36))
        .andExpect(jsonPath("$.lastCutoff").isNotEmpty())
        .andExpect(jsonPath("$.updatedAt").isNotEmpty());
  }

  @Test
  void theEndpointAnswersAChangeWithTheNewValue() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/admin/permission-history/retention")
                .with(devUser("dev-admin"))
                .content(
                    """
                    {"retentionMonths": 60}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.retentionMonths").value(60));

    assertThat(repository.findSingleton().orElseThrow().getRetentionMonths()).isEqualTo(60);
  }

  @Test
  void theEndpointRefusesAValueOutsideTheBounds() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/admin/permission-history/retention")
                .with(devUser("dev-admin"))
                .content(
                    """
                    {"retentionMonths": 121}
                    """))
        .andExpect(status().isBadRequest());

    assertThat(repository.findSingleton().orElseThrow().getRetentionMonths()).isEqualTo(36);
  }

  @Test
  void theEndpointIsClosedToEverybodyButTheSystemAdministration() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/permission-history/retention").with(devUser("dev-user")))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            put("/api/v1/admin/permission-history/retention")
                .with(devUser("dev-user"))
                .content(
                    """
                    {"retentionMonths": 60}
                    """))
        .andExpect(status().isForbidden());

    assertThat(repository.findSingleton().orElseThrow().getRetentionMonths()).isEqualTo(36);
  }

  private static RequestPostProcessor devUser(String subject) {
    return request -> {
      request.addHeader(DevAuthFilter.DEV_USER_HEADER, subject);
      request.setContentType(MediaType.APPLICATION_JSON_VALUE);
      return request;
    };
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
