/**
 * Design variants a UI lab can show side by side in one prototype build, chosen with
 * `?variant=<id>` and remembered in this tab's sessionStorage like the mock scenario. Empty on
 * `main`: a lab branch registers its variants here and lets components branch on
 * {@link currentUiLabVariant}. Without a registered variant every caller sees `null`.
 */
export const UI_LAB_VARIANTS: readonly { id: string; label: string }[] = [
  { id: 'ist-live', label: 'Ist · Live' },
  { id: 'a-batch', label: 'A Im Feld · Diktat' },
  { id: 'a-live', label: 'A Im Feld · Live' },
  { id: 'b-batch', label: 'B Aufnahmekarte · Diktat' },
  { id: 'b-live', label: 'B Aufnahmekarte · Live' },
  { id: 'c-batch', label: 'C Direkt ins Feld · Diktat' },
  { id: 'c-live', label: 'C Direkt ins Feld · Live' },
]

const STORAGE_KEY = 'opaa.uiLabVariant'

/**
 * The active variant id: a known `?variant=` wins and is remembered, an unknown one (`none`)
 * forgets the remembered one; without the parameter the remembered one applies.
 */
export function currentUiLabVariant(): string | null {
  if (typeof window === 'undefined') return null
  const known = (id: string | null) => UI_LAB_VARIANTS.some((variant) => variant.id === id)
  try {
    const fromQuery = new URLSearchParams(window.location.search).get('variant')
    if (fromQuery !== null) {
      if (known(fromQuery)) {
        window.sessionStorage.setItem(STORAGE_KEY, fromQuery)
        return fromQuery
      }
      window.sessionStorage.removeItem(STORAGE_KEY)
      return null
    }
    const stored = window.sessionStorage.getItem(STORAGE_KEY)
    return known(stored) ? stored : null
  } catch {
    return null
  }
}
