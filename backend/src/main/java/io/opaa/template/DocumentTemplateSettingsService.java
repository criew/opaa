package io.opaa.template;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.NotFoundException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The installation switch "Vorlagen", off as delivered. {@link #isEnabled()} is read on every
 * request that touches a template, without a cache: one primary-key lookup, and switching takes
 * effect on every instance at once without a restart (ADR-0053). An absent row is the delivered
 * state.
 *
 * <p>Every change is audited as {@link AuditEventType#DOCUMENT_TEMPLATE_SETTINGS_CHANGED}.
 */
@Service
public class DocumentTemplateSettingsService {

  private static final String SETTINGS_OBJECT_ID = "document_template_settings";
  private static final String SETTINGS_OBJECT_LABEL = "Vorlagen";

  /** The switch, when and by whom it was changed last ({@code null} while as delivered). */
  public record View(boolean enabled, Instant updatedAt, String updatedBy) {}

  private record Row(boolean enabled, Instant updatedAt, UUID updatedBy) {}

  private final NamedParameterJdbcTemplate jdbc;
  private final UserRepository users;
  private final AuditEventRecorder audit;

  DocumentTemplateSettingsService(
      NamedParameterJdbcTemplate jdbc, UserRepository users, AuditEventRecorder audit) {
    this.jdbc = jdbc;
    this.users = users;
    this.audit = audit;
  }

  /** Whether the installation offers document templates right now. */
  public boolean isEnabled() {
    return row().map(Row::enabled).orElse(false);
  }

  /** The 404 every template path answers while the switch is off. */
  public void requireEnabled() {
    if (!isEnabled()) {
      throw new NotFoundException("Objekt nicht gefunden");
    }
  }

  /** The switch as saved, for the disclosure; the name of who changed it is left out. */
  View current() {
    return row()
        .map(r -> new View(r.enabled(), r.updatedAt(), null))
        .orElse(new View(false, null, null));
  }

  @Transactional(readOnly = true)
  public View get(CurrentUser caller) {
    caller.requireSystemAdmin("Nur die Systemverwaltung darf den Schalter „Vorlagen“ sehen.");
    return view(row(), caller.organizationId());
  }

  @Transactional
  public View update(boolean enabled, CurrentUser caller) {
    caller.requireSystemAdmin("Nur die Systemverwaltung darf den Schalter „Vorlagen“ ändern.");
    boolean before = isEnabled();
    jdbc.update(
        "INSERT INTO document_template_settings (id, enabled, updated_at, updated_by)"
            + " VALUES (1, :enabled, clock_timestamp(), :by)"
            + " ON CONFLICT (id) DO UPDATE SET enabled = EXCLUDED.enabled,"
            + " updated_at = EXCLUDED.updated_at, updated_by = EXCLUDED.updated_by",
        Map.of("enabled", enabled, "by", caller.id()));
    if (before != enabled) {
      audit.recordUserAction(
          AuditEvent.builder()
              .organizationId(caller.organizationId())
              .actor(caller.id())
              .type(AuditEventType.DOCUMENT_TEMPLATE_SETTINGS_CHANGED)
              .object(
                  AuditObjectType.SYSTEM_SETTING,
                  UUID.nameUUIDFromBytes(SETTINGS_OBJECT_ID.getBytes(StandardCharsets.UTF_8)),
                  SETTINGS_OBJECT_LABEL)
              .before(Map.of("enabled", before))
              .after(Map.of("enabled", enabled))
              .outcome(AuditOutcome.SUCCESS)
              .build());
    }
    return view(row(), caller.organizationId());
  }

  private Optional<Row> row() {
    return jdbc
        .query(
            "SELECT enabled, updated_at, updated_by FROM document_template_settings WHERE id = 1",
            Map.of(),
            (rs, index) ->
                new Row(
                    rs.getBoolean("enabled"),
                    rs.getTimestamp("updated_at").toInstant(),
                    rs.getObject("updated_by", UUID.class)))
        .stream()
        .findFirst();
  }

  private View view(Optional<Row> row, UUID viewerOrganizationId) {
    if (row.isEmpty()) {
      return new View(false, null, null);
    }
    Row stored = row.get();
    String updatedBy =
        stored.updatedBy() == null
            ? null
            : users
                .findByIdAndOrganizationId(stored.updatedBy(), viewerOrganizationId)
                .map(User::getDisplayName)
                .orElse(null);
    return new View(stored.enabled(), stored.updatedAt(), updatedBy);
  }
}
