export const CONSENT_INTENT_STORAGE_KEY = 'opaa.sourceConsent'

export function forgetConsentIntent(): void {
  try {
    sessionStorage.removeItem(CONSENT_INTENT_STORAGE_KEY)
  } catch {
    // nothing stored then
  }
}
