interface InitialSource {
  displayName?: string | null
  email?: string | null
}

/**
 * The single letter the avatar shows. `??` alone is not enough: an IdP may deliver
 * displayName as an empty or blank string, and `''[0].toUpperCase()` throws - the shared
 * helper trims first (#800, review #795 finding 5). Shared by GlobalRail and SettingsPage
 * so the fallback chain cannot drift apart.
 */
export function userInitial(user: InitialSource | null | undefined): string {
  const source = user?.displayName?.trim() || user?.email?.trim() || '?'
  return source[0].toUpperCase()
}
