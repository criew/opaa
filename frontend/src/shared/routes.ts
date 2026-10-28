/** Routes referenced from more than one module. Paths stay English (AGENTS.md). */
export const LOGIN_ROUTE = '/login'
export const SYSTEM_LOGIN_ROUTE = '/login/system'
export const PASSWORD_ROUTE = '/account/password'
export const FORGOT_PASSWORD_ROUTE = '/forgot-password'
export const REGISTER_ROUTE = '/register'
/** Target of both link kinds in the mails - an invitation and an administrative reset. */
export const SET_PASSWORD_ROUTE = '/set-password'
export const VERIFY_EMAIL_ROUTE = '/verify-email'
/** Target of the handover link; the page redeems the code after the provider sign-in. */
export const HANDOVER_ROUTE = '/handover'
/** Where every provider sign-in returns - the one route a running handover may pass through. */
export const AUTH_CALLBACK_ROUTE = '/auth/callback'
/** The set-up assistant; while the installation is pending, the only page the frame shows. */
export const INSTALLATION_ROUTE = '/installation'
export const SETTINGS_ROUTE = '/settings'
/** The person's own access tokens for external AI tools, a tab of the settings. */
export const OWN_TOKENS_ROUTE = '/settings/tokens'
/** The person's own connected accounts, a tab of the settings. */
export const CONNECTED_ACCOUNTS_ROUTE = '/settings/accounts'
/** The person's own self-disclosure, a tab of the settings; a forced sign-in returns here. */
export const MY_DATA_ROUTE = '/settings/data'
/** Where a provider returns after an OAuth consent; the server names it as the redirect URI. */
export const CONNECTION_CALLBACK_ROUTE = '/connections/callback'
/** The administration's area „Anbindungen"; a bare path lands on its first tab. */
export const CONNECTIONS_ADMIN_ROUTE = '/admin/connections'
/** The tabs of the area, in the order of the tab bar. */
export const CONNECTIONS_ADMIN_TABS = [
  'access',
  'source-types',
  'external-tools',
  'settings',
] as const
export type ConnectionsAdminTab = (typeof CONNECTIONS_ADMIN_TABS)[number]
export function connectionsAdminRoute(tab: ConnectionsAdminTab): string {
  return `${CONNECTIONS_ADMIN_ROUTE}/${tab}`
}
/** The system administration's connection profiles. */
export const CONNECTION_PROFILES_ROUTE = connectionsAdminRoute('access')
/** One profile in the list, e.g. from a notification about it. */
export function connectionProfileRoute(profileId: string): string {
  return `${CONNECTION_PROFILES_ROUTE}?profile=${encodeURIComponent(profileId)}`
}
/** The external AI tools: their settings panel and the anchors of the two lists. */
export const EXTERNAL_TOOLS_ROUTE = connectionsAdminRoute('external-tools')
export const EXTERNAL_TOOLS_SETTINGS_ROUTE = `${EXTERNAL_TOOLS_ROUTE}?panel=settings`
export const EXTERNAL_TOOLS_TOKENS_ROUTE = `${EXTERNAL_TOOLS_ROUTE}#tokens`
export const EXTERNAL_TOOLS_LIBRARIES_ROUTE = `${EXTERNAL_TOOLS_ROUTE}#libraries`
/** The audit's read view of the connection log, next to the rights history. */
export const CONNECTION_LOG_ROUTE = '/revision/verbindungsprotokoll'
export const RIGHTS_HISTORY_ROUTE = '/revision/rechtehistorie'
/** The one entry for every asset type (ADR-0039, Entscheidung 1). */
export const CATALOG_ROUTE = '/catalog'
/** "Neuer Katalogeintrag" in the catalog: the type choice before the type's own wizard. */
export const CATALOG_NEW_ROUTE = '/catalog/new'
/**
 * The tabs of the space settings, in the order of the tab bar; the first is where a link without
 * a tab of its own lands. Every asset type shares the tab `content`.
 */
export const SPACE_SETTINGS_TABS = ['general', 'members', 'content'] as const
export type SpaceSettingsTab = (typeof SPACE_SETTINGS_TABS)[number]

/** Former tabs that bookmarks and links may still name, and the tab that replaced them. */
export const FORMER_SPACE_SETTINGS_TABS: Readonly<Record<string, SpaceSettingsTab>> = {
  knowledge: 'content',
  prompts: 'content',
}

export function spaceSettingsRoute(
  spaceId: string,
  tab: SpaceSettingsTab = SPACE_SETTINGS_TABS[0],
): string {
  return `/spaces/${spaceId}/settings/${tab}`
}
/**
 * The areas of a prompt library's detail page; the first one is where `/prompts/:id` lands. The
 * area names are those of the knowledge library's `?tab=`, so both detail pages read alike.
 */
export const PROMPT_LIBRARY_TABS = ['prompts', 'freigaben', 'zuordnungen'] as const
export type PromptLibraryTab = (typeof PROMPT_LIBRARY_TABS)[number]

/** Former areas that bookmarks and links may still name, and the area that replaced them. */
export const FORMER_PROMPT_LIBRARY_TABS: Readonly<Record<string, PromptLibraryTab>> = {
  settings: 'freigaben',
}

export function promptLibraryRoute(
  promptLibraryId: string,
  tab: PromptLibraryTab = PROMPT_LIBRARY_TABS[0],
): string {
  return tab === PROMPT_LIBRARY_TABS[0]
    ? `/prompts/${promptLibraryId}`
    : `/prompts/${promptLibraryId}/${tab}`
}

/** Where a sign-in lands when it carries no target of its own. */
export const AFTER_SIGN_IN_ROUTE = '/chat'
