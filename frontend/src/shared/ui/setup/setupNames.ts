/**
 * The name of every setting the installation assistant and the administration both ask for
 * (guidelines 5.17): the assistant's step and the administration's tab or navigation entry read
 * this one name, so the two can never drift apart.
 */
export const SETUP_NAMES = {
  signIn: 'Identitätsanbieter',
  chatModel: 'Chat-Modell',
  embeddingModel: 'Einbettung',
  rerankModel: 'Reranking',
  transcriptionModel: 'Spracheingabe',
  textRecognitionModel: 'Texterkennung',
  malwareScan: 'Virenprüfung',
  mail: 'E-Mail',
  branding: 'Branding',
} as const

/** The one word for the key of a model service, in every form. */
export const API_KEY_LABEL = 'API-Schlüssel'

/** Vertical rhythm inside a field block, in MUI spacing steps. */
export const SETUP_RHYTHM = {
  /** Between two fields or groups of a block. */
  betweenFields: 3,
  /** A field and the notice or quiet action that belongs to it. */
  fieldToOwn: 1,
} as const

/** Field name → message; a field without message is valid. */
export type SetupErrors = Readonly<Record<string, string | undefined>>

export function isBlank(value: string): boolean {
  return value.trim() === ''
}

/** An absolute http(s) address; whether it is reachable is the check's business. */
export function urlError(value: string, what: string, optional = false): string | undefined {
  if (isBlank(value)) return optional ? undefined : `Bitte ${what} angeben.`
  try {
    const url = new URL(value.trim())
    if (url.protocol === 'http:' || url.protocol === 'https:') return undefined
  } catch {
    // falls through to the message below
  }
  return `${what.charAt(0).toUpperCase()}${what.slice(1)} beginnt mit http:// oder https://.`
}
