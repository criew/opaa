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

  /**
   * The current values: every key of the section, {@code null} where the factory default applies,
   * only JSON values (numbers, booleans, strings, lists, maps). Never a secret, not even whether
   * one is stored, and never who changed a value.
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
