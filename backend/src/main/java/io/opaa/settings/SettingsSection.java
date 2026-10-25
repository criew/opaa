package io.opaa.settings;

import io.opaa.auth.CurrentUser;
import java.util.Map;
import java.util.Set;

/**
 * One section of the settings document: the settings of one topic, exported without secrets and
 * applied through the same write path the administration uses, so the import validates, checks the
 * limits of the operation and audits exactly like the user interface. Every setting of the
 * installation belongs to one section; {@code SettingsSectionRegistryTest} finds a settings table
 * none claims.
 */
public interface SettingsSection {

  /** The stable name of the section in the document, lowerCamelCase; never reused for another. */
  String key();

  /** The German name of the section in the administration, for the preview. */
  String label();

  /** The tables whose state this section carries. */
  Set<String> tables();

  /** The German name of every key {@link #export} writes, for the preview. */
  Map<String, String> keyLabels();

  /**
   * The keys whose change widens or redirects what the installation reaches or admits - where mail
   * goes, which networks and links are allowed, who may register - and is marked in the preview.
   */
  default Set<String> securityRelevantKeys() {
    return Set.of();
  }

  /**
   * The values in force: every key of the section with its stored value. Only JSON values (numbers,
   * booleans, strings, lists, maps); what {@code null} means is the section's own (for branding the
   * OPAA default, for the quota the configured default, for a mail server field "not set"). Never a
   * secret, not even whether one is stored, and never who changed a value.
   */
  Map<String, Object> export(CurrentUser actor);

  /**
   * Applies {@code values} - every key {@link #export} writes, unchanged ones included - through
   * the regular write path, inside the caller's transaction. A value outside its bounds or the
   * limits of the operation throws as the administration would; a secret of the installation stays
   * as it is.
   */
  void apply(CurrentUser actor, SettingsValues values);
}
