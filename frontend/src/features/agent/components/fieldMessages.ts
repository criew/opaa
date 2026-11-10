import type { FieldError } from '@/shared/session/types'

/** The backend's field errors by field, the first message of each. */
export function fieldMessages(errors: FieldError[]): Record<string, string> {
  const messages: Record<string, string> = {}
  for (const error of errors) {
    if (!(error.field in messages)) messages[error.field] = error.message
  }
  return messages
}
