package io.opaa.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ValidationException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

class RetentionSettingTest {

  private static final String TABLE = "example_retention_settings";

  private final AuditEventRecorder recorder = mock(AuditEventRecorder.class);
  private final RetentionSetting setting =
      new RetentionSetting(
          recorder,
          TABLE,
          "Nur die Administration",
          AuditEventType.CONNECTION_LOG_RETENTION_CHANGED,
          "Beispielfrist",
          new RetentionSetting.Bounds(12, 120, "Monaten", " (1 bis 10 Jahre)"));
  private final CurrentUser admin =
      CurrentUser.of(UUID.randomUUID(), UUID.randomUUID(), SystemRole.SYSTEM_ADMIN, "Admin");
  private final CurrentUser member =
      CurrentUser.of(UUID.randomUUID(), UUID.randomUUID(), SystemRole.USER, "Member");

  @Test
  void objectIdIsTheNameBasedUuidOfTheTable() {
    assertThat(RetentionSetting.objectId(TABLE))
        .isEqualTo(UUID.nameUUIDFromBytes(TABLE.getBytes(StandardCharsets.UTF_8)));
  }

  /**
   * The audit object id of every retention setting stays what earlier entries carry; the services
   * are named as strings because they live in modules above {@code settings}.
   */
  @ParameterizedTest
  @CsvSource({
    "io.opaa.query.runlog.AgentRunLogRetentionService, 751e9ee8-6012-38c4-adfa-a0a38b0cb15b",
    "io.opaa.connection.log.ConnectionLogRetentionService, 38de752d-7282-35a0-923e-d8751817acb8",
    "io.opaa.diagnosticaccess.DiagnosticContextRetentionService,"
        + " ca313e34-f113-34ff-9206-62339c78c7e7",
    "io.opaa.permission.PermissionHistoryRetentionService, 5935e512-5b28-363b-a6c5-1b7ef395e12c"
  })
  void everyRetentionServiceKeepsItsAuditObjectId(String service, UUID expected)
      throws ReflectiveOperationException {
    Constructor<?> constructor = Class.forName(service).getDeclaredConstructors()[0];
    constructor.setAccessible(true);
    Object instance =
        constructor.newInstance(
            Arrays.stream(constructor.getParameterTypes()).map(type -> mock(type)).toArray());
    Field field = instance.getClass().getDeclaredField("setting");
    field.setAccessible(true);

    assertThat(((RetentionSetting) field.get(instance)).objectId()).isEqualTo(expected);
  }

  @Test
  void requireAdminRejectsEveryoneButTheSystemAdministration() {
    assertThatCode(() -> setting.requireAdmin(admin)).doesNotThrowAnyException();
    assertThatThrownBy(() -> setting.requireAdmin(member))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessage("Nur die Administration");
  }

  @Test
  void requireWithinAcceptsBothBoundsAndNamesSubjectUnitAndNoteOutside() {
    assertThatCode(
            () -> {
              setting.requireWithin("X", 12);
              setting.requireWithin("X", 120);
            })
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> setting.requireWithin("Die Frist", 11))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Die Frist muss zwischen 12 und 120 Monaten liegen (1 bis 10 Jahre)");
    assertThatThrownBy(() -> setting.requireWithin("Die Frist", 121))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Die Frist muss zwischen 12 und 120 Monaten liegen (1 bis 10 Jahre)");
  }

  @Test
  void changeWritesAndRecordsBeforeAndAfterOnTheSettingsObject() {
    setting.change(admin, Map.of("retentionMonths", 6), Map.of("retentionMonths", 9), () -> 1);

    ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
    verify(recorder).recordUserAction(event.capture());
    assertThat(event.getValue().organizationId()).isEqualTo(admin.organizationId());
    assertThat(event.getValue().actorUserId()).isEqualTo(admin.id());
    assertThat(event.getValue().eventType())
        .isEqualTo(AuditEventType.CONNECTION_LOG_RETENTION_CHANGED);
    assertThat(event.getValue().objectType()).isEqualTo(AuditObjectType.SYSTEM_SETTING);
    assertThat(event.getValue().objectId()).isEqualTo(RetentionSetting.objectId(TABLE));
    assertThat(event.getValue().objectLabel()).isEqualTo("Beispielfrist");
    assertThat(event.getValue().before()).isEqualTo(Map.of("retentionMonths", 6));
    assertThat(event.getValue().after()).isEqualTo(Map.of("retentionMonths", 9));
    assertThat(event.getValue().outcome()).isEqualTo(AuditOutcome.SUCCESS);
  }

  @Test
  void changeFailsWithoutAuditWhenTheUpdateMissesTheSingletonRow() {
    assertThatThrownBy(
            () ->
                setting.change(
                    admin, Map.of("retentionMonths", 6), Map.of("retentionMonths", 9), () -> 0))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(TABLE);
    verify(recorder, never()).recordUserAction(any());
  }
}
