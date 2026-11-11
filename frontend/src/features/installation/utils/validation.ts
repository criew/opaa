import type { FieldError } from '@/shared/session/types'
import { isBlank, urlError } from '@/shared/ui/setup/setupNames'
import { STEP_REQUEST_KEY } from '../api/types'

export { isBlank, urlError }

/** Field name → German message; an empty record means the entries can be sent. */
export type FieldErrors = Readonly<Record<string, string>>

/** The address the backend refuses for the first account (ADR-0060). */
export const RESERVED_ADMIN_EMAIL = 'admin@opaa.local'

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
const HEX_COLOR_PATTERN = /^#[0-9a-fA-F]{6}$/

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

export function hexColorError(value: string): string | undefined {
  if (isBlank(value)) return 'Bitte eine Farbe angeben.'
  return HEX_COLOR_PATTERN.test(value.trim())
    ? undefined
    : 'Die Farbe steht als Hex-Wert mit sechs Stellen, etwa #1292EE.'
}

/** Only the fields that carry a message. */
export function compact(errors: Record<string, string | undefined>): FieldErrors {
  return Object.fromEntries(
    Object.entries(errors).filter((entry): entry is [string, string] => Boolean(entry[1])),
  )
}

const REQUEST_PREFIX = new RegExp(`^(${Object.values(STEP_REQUEST_KEY).join('|')})\\.`)

/**
 * The backend's field errors by field, named as in the step's values (`firstAdmin.email` and
 * `email` both become `email`); the first message per field wins.
 */
export function byField(fieldErrors: readonly FieldError[]): FieldErrors {
  return fieldErrors.reduce<Record<string, string>>((acc, error) => {
    const field = error.field.replace(REQUEST_PREFIX, '')
    return acc[field] ? acc : { ...acc, [field]: error.message }
  }, {})
}
