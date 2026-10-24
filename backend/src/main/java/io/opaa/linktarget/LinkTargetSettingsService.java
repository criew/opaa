package io.opaa.linktarget;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and replaces the allowed link targets; the only writer of {@code link_target_settings}.
 * Read per call and never cached, so a change acts on every instance at once. Every change is the
 * audit event {@code LINK_TARGETS_CHANGED} with the whole list before and after.
 */
@Service
public class LinkTargetSettingsService {

  private static final UUID SETTINGS_OBJECT_ID =
      UUID.nameUUIDFromBytes("link-targets".getBytes(StandardCharsets.UTF_8));

  private static final String SELECT =
      "SELECT allowed_origins, updated_at FROM link_target_settings WHERE id = 1";

  private final JdbcTemplate jdbc;
  private final AuditEventRecorder audit;

  public LinkTargetSettingsService(JdbcTemplate jdbc, AuditEventRecorder audit) {
    this.jdbc = jdbc;
    this.audit = audit;
  }

  /** The current list; one primary-key read. */
  @Transactional(readOnly = true)
  public LinkTargetSettings current() {
    return jdbc.queryForObject(SELECT, (rs, i) -> settings(rs));
  }

  /**
   * Replaces the list with {@code origins} after {@link LinkTargetOrigins#normalizeAll}; an
   * unchanged list writes nothing and records nothing.
   */
  @Transactional
  public LinkTargetSettings replace(UUID organizationId, UUID actorUserId, List<String> origins) {
    List<String> normalized = LinkTargetOrigins.normalizeAll(origins);
    LinkTargetSettings before =
        jdbc.queryForObject(SELECT + " FOR UPDATE", (rs, i) -> settings(rs));
    if (before.allowedOrigins().equals(normalized)) {
      return before;
    }
    Instant updatedAt =
        jdbc.query(
            connection -> {
              var statement =
                  connection.prepareStatement(
                      "UPDATE link_target_settings SET allowed_origins = ?,"
                          + " updated_at = clock_timestamp(), updated_by = ? WHERE id = 1"
                          + " RETURNING updated_at");
              statement.setArray(1, connection.createArrayOf("text", normalized.toArray()));
              statement.setObject(2, actorUserId);
              return statement;
            },
            rs -> {
              rs.next();
              return rs.getTimestamp(1).toInstant();
            });
    audit.recordUserAction(
        AuditEvent.builder()
            .organizationId(organizationId)
            .actor(actorUserId)
            .type(AuditEventType.LINK_TARGETS_CHANGED)
            .object(AuditObjectType.SYSTEM_SETTING, SETTINGS_OBJECT_ID, "Erlaubte Linkziele")
            .before(Map.of("allowedOrigins", before.allowedOrigins()))
            .after(Map.of("allowedOrigins", normalized))
            .outcome(AuditOutcome.SUCCESS)
            .build());
    return new LinkTargetSettings(normalized, updatedAt);
  }

  private static LinkTargetSettings settings(ResultSet rs) throws SQLException {
    Array array = rs.getArray("allowed_origins");
    List<String> origins = array == null ? List.of() : List.of((String[]) array.getArray());
    return new LinkTargetSettings(origins, rs.getTimestamp("updated_at").toInstant());
  }
}
