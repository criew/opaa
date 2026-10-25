package io.opaa.connection;

import io.opaa.auth.CurrentUser;
import io.opaa.outbound.OutboundProxy;
import io.opaa.settings.SettingsSection;
import io.opaa.settings.SettingsValues;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** The house-wide outbound proxy and its exclusion list; {@code null} for direct connections. */
@Component
class OutboundProxySettingsSection implements SettingsSection {

  private final OutboundProxyAdministration administration;

  OutboundProxySettingsSection(OutboundProxyAdministration administration) {
    this.administration = administration;
  }

  @Override
  public String key() {
    return "outboundProxy";
  }

  @Override
  public String label() {
    return "Ausgangsproxy";
  }

  @Override
  public Set<String> tables() {
    return Set.of("outbound_proxy_settings");
  }

  @Override
  public Map<String, String> keyLabels() {
    return Map.of("proxyUrl", "Proxy-Adresse", "noProxy", "Ausnahmeliste");
  }

  @Override
  public Set<String> securityRelevantKeys() {
    return Set.of("proxyUrl", "noProxy");
  }

  @Override
  public Map<String, Object> export(CurrentUser actor) {
    OutboundProxy.Settings settings = administration.read(actor);
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("proxyUrl", settings.proxy() == null ? null : settings.proxy().toUrl());
    values.put("noProxy", settings.noProxy());
    return values;
  }

  @Override
  public void apply(CurrentUser actor, SettingsValues values) {
    administration.change(
        actor, values.optionalString("proxyUrl"), values.requireStrings("noProxy"));
  }
}
