import { currentUiLabVariant } from '@/shared/uiLabVariant'

/** The document-view design of the UI lab (#3874): `null` is the current state. */
export type DocViewVariant = 'a' | 'b' | 'c' | null

export function docViewVariant(): DocViewVariant {
  const variant = currentUiLabVariant()
  return variant === 'a' || variant === 'b' || variant === 'c' ? variant : null
}

/** Brand colours of the concept (Epic #3865). */
export const BRAND = {
  navy: '#242855',
  blue: '#0055A4',
  light: '#009DDB',
  /** Light blue as an opaque marker, multiplied onto the page: dark text stays dark on it. */
  marker: '#B5E2F5',
  markerStrong: '#86CFEF',
  markerSoft: '#DDF2FB',
} as const

/** The desk the pages lie on, per scheme. */
export function deskColor(variant: DocViewVariant, dark: boolean): string {
  if (variant === 'c') return dark ? '#14163A' : '#E4E8F1'
  return dark ? '#191C45' : '#EBEEF4'
}
