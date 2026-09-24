/** Routes referenced from more than one module. Paths stay English (AGENTS.md). */
export const LOGIN_ROUTE = '/login'
export const SYSTEM_LOGIN_ROUTE = '/login/system'
export const PASSWORD_ROUTE = '/account/password'
export const FORGOT_PASSWORD_ROUTE = '/forgot-password'
export const REGISTER_ROUTE = '/register'
/** Target of both link kinds in the mails - an invitation and an administrative reset (#1540). */
export const SET_PASSWORD_ROUTE = '/set-password'
export const VERIFY_EMAIL_ROUTE = '/verify-email'
/** Target of the handover link (#1563); the page redeems the code after the provider sign-in. */
export const HANDOVER_ROUTE = '/handover'
/** Where every provider sign-in returns - the one route a running handover may pass through. */
export const AUTH_CALLBACK_ROUTE = '/auth/callback'
export const SETTINGS_ROUTE = '/settings'
/**
 * Die Reiter der Space-Einstellungen (#1917). Ein weiterer Asset-Typ ist ein weiterer Wert hier
 * und ein weiterer Eintrag in SpaceSettingsPage; die Reihenfolge ist die der Reiterleiste, der
 * erste Wert das Ziel eines Verweises ohne eigenen Reiter.
 */
export const SPACE_SETTINGS_TABS = ['general', 'members', 'knowledge'] as const
export type SpaceSettingsTab = (typeof SPACE_SETTINGS_TABS)[number]

export function spaceSettingsRoute(
  spaceId: string,
  tab: SpaceSettingsTab = SPACE_SETTINGS_TABS[0],
): string {
  return `/spaces/${spaceId}/settings/${tab}`
}
/** Where a sign-in lands when it carries no target of its own. */
export const AFTER_SIGN_IN_ROUTE = '/chat'
