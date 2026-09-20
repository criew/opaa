package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.PermissionHistoryRetentionResponse;
import io.opaa.permission.PermissionHistoryRetentionSettings;
import java.lang.reflect.Field;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Field-by-field cover of the one mapper of {@code /api/v1/admin/permission-history/retention}: the
 * entity is read-only and has no setter, so the fixture is built reflectively rather than through a
 * path production code does not have.
 */
class PermissionHistoryRetentionResponseMapperTest {

  @Test
  void everyFieldOfTheSettingsRowReachesTheResponse() throws Exception {
    Instant lastCutoff = Instant.parse("2023-09-01T00:00:00Z");
    Instant updatedAt = Instant.parse("2026-09-20T10:15:30Z");
    PermissionHistoryRetentionSettings settings = settings(48, lastCutoff, updatedAt);

    PermissionHistoryRetentionResponse response =
        PermissionHistoryRetentionResponseMapper.toResponse(settings);

    assertThat(response.getRetentionMonths()).isEqualTo(48);
    assertThat(response.getLastCutoff()).isEqualTo(lastCutoff);
    assertThat(response.getUpdatedAt()).isEqualTo(updatedAt);
  }

  /** A pass that has not run yet leaves the cutoff empty; the response must carry that through. */
  @Test
  void anUnsetCutoffStaysUnset() throws Exception {
    PermissionHistoryRetentionResponse response =
        PermissionHistoryRetentionResponseMapper.toResponse(
            settings(36, null, Instant.parse("2026-09-20T10:15:30Z")));

    assertThat(response.getLastCutoff()).isNull();
    assertThat(response.getRetentionMonths()).isEqualTo(36);
  }

  private static PermissionHistoryRetentionSettings settings(
      int retentionMonths, Instant lastCutoff, Instant updatedAt) throws Exception {
    PermissionHistoryRetentionSettings settings = instantiate();
    set(settings, "retentionMonths", retentionMonths);
    set(settings, "lastCutoff", lastCutoff);
    set(settings, "updatedAt", updatedAt);
    return settings;
  }

  private static PermissionHistoryRetentionSettings instantiate() throws Exception {
    var constructor = PermissionHistoryRetentionSettings.class.getDeclaredConstructor();
    constructor.setAccessible(true);
    return constructor.newInstance();
  }

  private static void set(PermissionHistoryRetentionSettings settings, String name, Object value)
      throws Exception {
    Field field = PermissionHistoryRetentionSettings.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(settings, value);
  }
}
