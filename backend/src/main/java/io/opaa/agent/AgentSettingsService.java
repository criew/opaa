package io.opaa.agent;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.FieldValidationException;
import io.opaa.common.FieldValidationException.FieldError;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The installation-wide settings of agents: the switch and the tools switched on (ADR-0070,
 * Entscheidung 6). Changing them is the system administration's; a change is audited as {@link
 * AuditEventType#AGENT_SETTINGS_CHANGED} with before/after of the changed keys. Switching agents on
 * provisions the delivered agent "Recherche" for the administrator's organization, once.
 */
@Service
public class AgentSettingsService {

  private static final String SETTINGS_OBJECT_ID = "agent_settings";
  private static final String SETTINGS_OBJECT_LABEL = "Agenten";

  /**
   * The settings in force and when and by whom they were changed last.
   *
   * @param updatedBy {@code null} unless that person belongs to the viewer's organization
   */
  public record View(
      boolean enabled, Set<String> enabledTools, Instant updatedAt, String updatedBy) {}

  private final AgentSettingsRepository settings;
  private final AgentResearchProvisioner provisioner;
  private final UserRepository users;
  private final AuditEventRecorder audit;
  private final Clock clock;

  AgentSettingsService(
      AgentSettingsRepository settings,
      AgentResearchProvisioner provisioner,
      UserRepository users,
      AuditEventRecorder audit,
      Clock clock) {
    this.settings = settings;
    this.provisioner = provisioner;
    this.users = users;
    this.audit = audit;
    this.clock = clock;
  }

  /** The tools switched on right now, read per call (ADR-0053). */
  @Transactional(readOnly = true)
  public Set<String> enabledTools() {
    return settings.findSingleton().map(AgentSettings::getEnabledTools).orElse(Set.of());
  }

  @Transactional(readOnly = true)
  public View current(UUID viewerOrganizationId) {
    AgentSettings row = requireRow();
    return view(row, viewerOrganizationId);
  }

  /** Replaces switch and tools (system administration only, checked by the caller). */
  @Transactional
  public View update(CurrentUser actor, boolean enabled, Set<String> enabledTools) {
    Set<String> tools = validateTools(enabledTools);
    AgentSettings row = requireRow();
    boolean enabledBefore = row.isEnabled();
    Set<String> toolsBefore = new TreeSet<>(row.getEnabledTools());
    Map<String, Object> before = new LinkedHashMap<>();
    Map<String, Object> after = new LinkedHashMap<>();
    if (enabledBefore != enabled) {
      before.put("enabled", enabledBefore);
      after.put("enabled", enabled);
    }
    if (!toolsBefore.equals(tools)) {
      before.put("enabledTools", List.copyOf(toolsBefore));
      after.put("enabledTools", List.copyOf(tools));
    }
    if (!before.isEmpty()) {
      row.replace(enabled, tools, actor.id(), clock.instant());
      settings.save(row);
      audit.recordUserAction(
          AuditEvent.builder()
              .organizationId(actor.organizationId())
              .actor(actor.id())
              .type(AuditEventType.AGENT_SETTINGS_CHANGED)
              .object(
                  AuditObjectType.SYSTEM_SETTING,
                  UUID.nameUUIDFromBytes(SETTINGS_OBJECT_ID.getBytes(StandardCharsets.UTF_8)),
                  SETTINGS_OBJECT_LABEL)
              .before(before)
              .after(after)
              .outcome(AuditOutcome.SUCCESS)
              .build());
    }
    if (enabled) {
      provisioner.provisionResearch(actor);
    }
    return view(row, actor.organizationId());
  }

  private AgentSettings requireRow() {
    return settings
        .findSingleton()
        .orElseThrow(() -> new IllegalStateException("agent_settings row is missing"));
  }

  private View view(AgentSettings row, UUID viewerOrganizationId) {
    return new View(
        row.isEnabled(),
        new TreeSet<>(row.getEnabledTools()),
        row.getUpdatedAt(),
        displayNameOf(row.getUpdatedBy(), viewerOrganizationId));
  }

  private String displayNameOf(UUID userId, UUID viewerOrganizationId) {
    if (userId == null || viewerOrganizationId == null) {
      return null;
    }
    return users
        .findByIdAndOrganizationId(userId, viewerOrganizationId)
        .map(User::getDisplayName)
        .orElse(null);
  }

  private static Set<String> validateTools(Set<String> tools) {
    Set<String> result = new TreeSet<>();
    for (String tool : tools == null ? Set.<String>of() : tools) {
      if (!AgentTools.ASSIGNABLE.contains(tool)) {
        throw new FieldValidationException(
            "Bitte prüfen Sie die markierten Angaben.",
            List.of(
                new FieldError("enabledTools", "UNKNOWN", "Unbekanntes Werkzeug: " + tool + ".")));
      }
      result.add(tool);
    }
    return result;
  }
}
