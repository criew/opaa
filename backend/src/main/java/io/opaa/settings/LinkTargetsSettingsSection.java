package io.opaa.settings;

import io.opaa.auth.CurrentUser;
import io.opaa.linktarget.LinkTargetSettingsService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** The allowed link targets of answers, the whole list. */
@Component
class LinkTargetsSettingsSection implements SettingsSection {

  private final LinkTargetSettingsService service;

  LinkTargetsSettingsSection(LinkTargetSettingsService service) {
    this.service = service;
  }

  @Override
  public String key() {
    return "linkTargets";
  }

  @Override
  public String label() {
    return "Erlaubte Linkziele";
  }

  @Override
  public Set<String> tables() {
    return Set.of("link_target_settings");
  }

  @Override
  public Map<String, Object> export(CurrentUser actor) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("allowedOrigins", service.current().allowedOrigins());
    return values;
  }

  @Override
  public void apply(CurrentUser actor, SettingsValues values) {
    service.replace(actor.organizationId(), actor.id(), values.requireStrings("allowedOrigins"));
  }
}
