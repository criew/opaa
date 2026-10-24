package io.opaa.selfdisclosure;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.common.ValidationException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and changes {@link SelfDisclosureSettings}; the only writer of {@code
 * self_disclosure_settings}. Every change is the audit event {@code
 * SELF_DISCLOSURE_SETTINGS_CHANGED} with before and after of the changed keys.
 */
@Service
public class SelfDisclosureSettingsService {

  private static final UUID SETTINGS_OBJECT_ID =
      UUID.nameUUIDFromBytes("self-disclosure".getBytes(StandardCharsets.UTF_8));

  private final JdbcTemplate jdbc;
  private final AuditEventRecorder audit;

  public SelfDisclosureSettingsService(JdbcTemplate jdbc, AuditEventRecorder audit) {
    this.jdbc = jdbc;
    this.audit = audit;
  }

  /** The current settings; one primary-key read, never cached. */
  @Transactional(readOnly = true)
  public SelfDisclosureSettings current() {
    return jdbc.queryForObject(
        "SELECT enabled, daily_limit, updated_at FROM self_disclosure_settings WHERE id = 1",
        (rs, i) ->
            new SelfDisclosureSettings(
                rs.getBoolean("enabled"),
                rs.getInt("daily_limit"),
                rs.getTimestamp("updated_at").toInstant()));
  }

  /**
   * Changes the given keys ({@code null} leaves one as it is) and records the change; an unchanged
   * value writes nothing. Rejects a daily limit outside 1 to 10 with a German message.
   */
  @Transactional
  public SelfDisclosureSettings update(
      UUID organizationId, UUID actorUserId, Boolean enabled, Integer dailyLimit) {
    if (dailyLimit != null
        && (dailyLimit < SelfDisclosureSettings.MIN_DAILY_LIMIT
            || dailyLimit > SelfDisclosureSettings.MAX_DAILY_LIMIT)) {
      throw new ValidationException(
          "Die Tagesgrenze der Selbstauskunft muss zwischen "
              + SelfDisclosureSettings.MIN_DAILY_LIMIT
              + " und "
              + SelfDisclosureSettings.MAX_DAILY_LIMIT
              + " liegen.");
    }
    SelfDisclosureSettings before =
        jdbc.queryForObject(
            "SELECT enabled, daily_limit, updated_at FROM self_disclosure_settings WHERE id = 1"
                + " FOR UPDATE",
            (rs, i) ->
                new SelfDisclosureSettings(
                    rs.getBoolean("enabled"),
                    rs.getInt("daily_limit"),
                    rs.getTimestamp("updated_at").toInstant()));
    Map<String, Object> previous = new LinkedHashMap<>();
    Map<String, Object> next = new LinkedHashMap<>();
    if (enabled != null && enabled != before.enabled()) {
      previous.put("enabled", before.enabled());
      next.put("enabled", enabled);
    }
    if (dailyLimit != null && dailyLimit != before.dailyLimit()) {
      previous.put("dailyLimit", before.dailyLimit());
      next.put("dailyLimit", dailyLimit);
    }
    if (next.isEmpty()) {
      return before;
    }
    Timestamp updatedAt =
        jdbc.queryForObject(
            "UPDATE self_disclosure_settings SET enabled = ?, daily_limit = ?,"
                + " updated_at = clock_timestamp(), updated_by = ? WHERE id = 1"
                + " RETURNING updated_at",
            Timestamp.class,
            enabled != null ? enabled : before.enabled(),
            dailyLimit != null ? dailyLimit : before.dailyLimit(),
            actorUserId);
    audit.recordUserAction(
        AuditEvent.builder()
            .organizationId(organizationId)
            .actor(actorUserId)
            .type(AuditEventType.SELF_DISCLOSURE_SETTINGS_CHANGED)
            .object(AuditObjectType.SYSTEM_SETTING, SETTINGS_OBJECT_ID, "Selbstauskunft")
            .before(previous)
            .after(next)
            .outcome(AuditOutcome.SUCCESS)
            .build());
    return new SelfDisclosureSettings(
        enabled != null ? enabled : before.enabled(),
        dailyLimit != null ? dailyLimit : before.dailyLimit(),
        updatedAt.toInstant());
  }
}
