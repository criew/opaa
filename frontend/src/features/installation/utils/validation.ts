import type { FieldError } from '@/shared/session/types'

/** Field name → German message; an empty record means the entries can be sent. */
export type FieldErrors = Readonly<Record<string, string>>

/** The address the backend refuses for the first account (ADR-0060). */
export const RESERVED_ADMIN_EMAIL = 'admin@opaa.local'

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
const HEX_COLOR_PATTERN = /^#[0-9a-fA-F]{6}$/

export function isBlank(value: string): boolean {
  return value.trim() === ''
}

/** A deliverable-looking address; the backend decides the rest. */
export function emailError(value: string): string | undefined {
  if (isBlank(value)) return 'Bitte eine E-Mail-Adresse angeben.'
  if (!EMAIL_PATTERN.test(value.trim())) {
    return 'Die E-Mail-Adresse ist nicht vollständig, etwa name@behoerde.example.'
  }
  if (value.trim().toLowerCase() === RESERVED_ADMIN_EMAIL) {
    return `${RESERVED_ADMIN_EMAIL} ist für das erste Konto nicht zulässig. Bitte eine zustellbare Adresse angeben.`
  }
  return undefined
}

/** An absolute http(s) address; whether it is reachable is the step's check. */
export function urlError(value: string, what: string, optional = false): string | undefined {
  if (isBlank(value)) return optional ? undefined : `Bitte ${what} angeben.`
  try {
    const url = new URL(value.trim())
    if (url.protocol === 'http:' || url.protocol === 'https:') return undefined
  } catch {
    // falls through to the message below
  }
  return `${capitalize(what)} beginnt mit http:// oder https://.`
}

export function hexColorError(value: string): string | undefined {
  if (isBlank(value)) return 'Bitte eine Farbe angeben.'
  return HEX_COLOR_PATTERN.test(value.trim())
    ? undefined
    : 'Die Farbe steht als Hex-Wert mit sechs Stellen, etwa #1292EE.'
}

function capitalize(text: string): string {
  return text.charAt(0).toUpperCase() + text.slice(1)
}

/** Only the fields that carry a message. */
export function compact(errors: Record<string, string | undefined>): FieldErrors {
  return Object.fromEntries(
    Object.entries(errors).filter((entry): entry is [string, string] => Boolean(entry[1])),
  )
}

/** The backend's field errors by field; the first message per field wins. */
export function byField(fieldErrors: readonly FieldError[]): FieldErrors {
  return fieldErrors.reduce<Record<string, string>>(
    (acc, error) => (acc[error.field] ? acc : { ...acc, [error.field]: error.message }),
    {},
  )
}
