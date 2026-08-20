import type { ColorScheme } from '../types/api'
import type { ThemeMode } from '../stores/uiStore'

/**
 * Translates the operator's configured colour scheme (#582's `ColorScheme`) into the interface's
 * own vocabulary. Two names for the same three states, because the API describes a deployment-wide
 * default while `ThemeMode` describes what one browser currently shows.
 */
export function colorSchemeToThemeMode(scheme: ColorScheme): ThemeMode {
  switch (scheme) {
    case 'LIGHT':
      return 'light'
    case 'DARK':
      return 'dark'
    default:
      return 'system'
  }
}

/**
 * Which colour scheme actually applies (#583). The precedence is the whole point:
 *
 * 1. What the user chose in their own settings - a personal choice is never overridden by an
 *    operator default, which would otherwise flip the interface out from under someone who
 *    deliberately picked light or dark.
 * 2. Otherwise the operator's configured default - the deployment's starting point for everyone
 *    who has not decided for themselves.
 * 3. `system` resolves against the browser's own preference, as it always has.
 */
export function resolveThemeMode(
  userChoice: ThemeMode | null,
  operatorDefault: ColorScheme,
): ThemeMode {
  return userChoice ?? colorSchemeToThemeMode(operatorDefault)
}
