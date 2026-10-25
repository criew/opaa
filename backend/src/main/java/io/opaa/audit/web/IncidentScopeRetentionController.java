package io.opaa.audit.web;

import io.opaa.api.dto.IncidentScopeRetention;
import io.opaa.api.dto.IncidentScopeRetentionUpdate;
import io.opaa.api.dto.RetentionPause;
import io.opaa.audit.AuditIncidentScopeRetentionSettings;
import io.opaa.audit.AuditIncidentScopeRetentionSettings.Setting;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.instance.RetiredRetentionVariables.Pause;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The period the grants of the anlassbezogene Klärung are kept, for the system administration only.
 */
@RestController
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
@RequestMapping("/api/v1/admin/retention/incident-scopes")
public class IncidentScopeRetentionController {

  private final AuditIncidentScopeRetentionSettings settings;

  public IncidentScopeRetentionController(AuditIncidentScopeRetentionSettings settings) {
    this.settings = settings;
  }

  @GetMapping
  public IncidentScopeRetention getIncidentScopeRetention() {
    return toResponse(settings.setting());
  }

  @PutMapping
  public IncidentScopeRetention updateIncidentScopeRetention(
      @Valid @RequestBody IncidentScopeRetentionUpdate request, @Caller CurrentUser caller) {
    return toResponse(
        settings.change(caller.organizationId(), caller.id(), request.getRetentionYears()));
  }

  private IncidentScopeRetention toResponse(Setting setting) {
    return new IncidentScopeRetention(
        setting.retention().incidentScopeRetentionYears(),
        setting.defaults().incidentScopeRetentionYears(),
        setting.overridden(),
        pausedBy(settings.pauses(setting)));
  }

  private static List<RetentionPause> pausedBy(List<Pause> pauses) {
    return pauses.stream()
        .map(
            pause ->
                new RetentionPause(pause.variable(), pause.retiredValue(), pause.effectiveValue()))
        .toList();
  }
}
