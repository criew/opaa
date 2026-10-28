import type { ColorScheme } from '@/shared/api/types'
import { OPAA_BRANDING } from '@/shared/branding/brandingStore'

/**
 * The branding fields as the branding administration and the installation assistant present them:
 * the same labels, hints, limits and rules in both places. The limits and the colour rule mirror
 * `BrandingSettingsRules` in the backend, which decides in the end. Every field left empty means
 * the OPAA standard.
 */

export const BRANDING_LIMITS = {
  productName: 60,
  claim: 120,
  aiNotice: 500,
} as const

/** Field labels without „(optional)"; each form marks optional fields in its own way. */
export const BRANDING_LABELS = {
  productName: 'Produktname',
  claim: 'Claim',
  aiNotice: 'Hinweis zur KI-Nutzung',
  primaryColor: 'Primärfarbe',
  colorPicker: 'Auswählen',
  colorScheme: 'Farbschema-Vorgabe',
  preview: 'Vorschau',
} as const

export const BRANDING_TEXTS = {
  productNameHint: `Erscheint in Seitenleiste, Anmeldeseite und Fenstertitel. Leer lassen für „${OPAA_BRANDING.productName}“.`,
  claimHint: `Kurzer Satz unter dem Produktnamen. Leer lassen für „${OPAA_BRANDING.claim}“.`,
  aiNoticeHint:
    'Erscheint im Chat vor der ersten Frage und unter dem Eingabefeld sowie als Vermerk ' +
    'beim Kopieren einer Antwort. Der Hinweis lässt sich ändern, aber nicht abschalten: Ein ' +
    'leeres Feld stellt den Standardtext wieder her. Zeilenumbrüche werden durch Leerzeichen ersetzt.',
  aiNoticeReset: 'Hinweis auf Standard zurücksetzen',
  primaryColorHint: 'Akzentfarbe für Schaltflächen, Verweise und Fokusrahmen.',
  primaryColorInvalid:
    'Bitte einen sechsstelligen Hex-Wert mit führendem „#“ angeben, zum Beispiel #1292EE.',
  colorSchemeHint:
    'Gilt für alle, die in ihren eigenen Einstellungen noch nichts gewählt haben. Eine ' +
    'persönliche Wahl bleibt unberührt.',
  previewHint:
    'So wirkt die Einstellung in beiden Farbschemata — beide sind gleichermaßen verbindlich.',
  contrastTitle: 'Kontrast unterschritten',
  contrastWarning:
    'Die gewählte Farbe erreicht den empfohlenen Kontrast nicht überall. Sie können ' +
    'trotzdem speichern — die Entscheidung liegt bei Ihrem Haus.',
} as const

export const BRANDING_COLOR_SCHEME_OPTIONS: ReadonlyArray<{ value: ColorScheme; label: string }> = [
  { value: 'LIGHT', label: 'Hell' },
  { value: 'SYSTEM', label: 'System' },
  { value: 'DARK', label: 'Dunkel' },
]

/** Line breaks become spaces as they are typed or pasted: the backend refuses them. */
export function singleLineNotice(value: string): string {
  return value.replace(/[\r\n\u2028\u2029]+/g, ' ')
}

/** Whether a non-blank colour is no six-digit hex value with a leading „#". */
export function isInvalidPrimaryColor(value: string): boolean {
  return value.trim() !== '' && !/^#[0-9a-f]{6}$/i.test(value.trim())
}
