package io.opaa.api.types;

/**
 * The colour scheme a deployment starts its users off in (#582, docs/design/guidelines.md#7).
 * {@link #SYSTEM} follows the operating system's own preference and is the OPAA default; {@link
 * #LIGHT} and {@link #DARK} are an operator's deliberate override.
 *
 * <p>Deliberately a deployment-wide starting point, not a per-user setting: a user's own choice in
 * the browser still wins over it (#583). A closed vocabulary, mirrored by the database check
 * constraint {@code chk_branding_settings_color_scheme} (migration 041); keep both in sync.
 */
public enum ColorScheme {
  LIGHT,
  DARK,
  SYSTEM
}
