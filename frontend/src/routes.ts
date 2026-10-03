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
/** The one entry for every asset type (ADR-0039, Entscheidung 1). */
export const CATALOG_ROUTE = '/catalog'
/** "Neu" in the catalog: the type choice before the type's own wizard. */
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
/** The areas of a prompt library's detail page; the first one is where `/prompts/:id` lands. */
export const PROMPT_LIBRARY_TABS = ['prompts', 'settings'] as const
export type PromptLibraryTab = (typeof PROMPT_LIBRARY_TABS)[number]

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
