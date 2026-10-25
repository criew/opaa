package io.opaa.settings;

import io.opaa.api.types.ColorScheme;
import io.opaa.auth.CurrentUser;
import io.opaa.branding.BrandingSettingsService;
import io.opaa.branding.BrandingSettingsView;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The texts, colour and colour scheme of the branding as stored, {@code null} for the OPAA default.
 * The three images are no part of the document: they are files, maintained where they are shown.
 */
@Component
class BrandingSettingsSection implements SettingsSection {

  private final BrandingSettingsService service;

  BrandingSettingsSection(BrandingSettingsService service) {
    this.service = service;
  }

  @Override
  public String key() {
    return "branding";
  }

  @Override
  public String label() {
    return "Branding";
  }

  @Override
  public Set<String> tables() {
    return Set.of("branding_settings");
  }

  @Override
  public Map<String, String> keyLabels() {
    return Map.of(
        "productName",
        "Produktname",
        "claim",
        "Claim",
        "aiNotice",
        "Hinweis zur KI-Nutzung",
        "primaryColor",
        "Akzentfarbe",
        "defaultColorScheme",
        "Farbschema-Vorgabe");
  }

  @Override
  public Map<String, Object> export(CurrentUser actor) {
    BrandingSettingsView stored = service.storedSettings();
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("productName", stored.productName());
    values.put("claim", stored.claim());
    values.put("aiNotice", stored.aiNotice());
    values.put("primaryColor", stored.primaryColor());
    values.put(
        "defaultColorScheme",
        stored.defaultColorScheme() == null ? null : stored.defaultColorScheme().name());
    return values;
  }

  @Override
  public void apply(CurrentUser actor, SettingsValues values) {
    service.updateBranding(
        actor.organizationId(),
        actor.id(),
        values.optionalString("productName"),
        values.optionalString("claim"),
        values.optionalString("aiNotice"),
        values.optionalString("primaryColor"),
        values.optionalEnum("defaultColorScheme", ColorScheme.class));
  }
}
