import type { SourceTypeKey } from '@/shared/api/types'
import { readConsentIntent, type SourceConsentIntent } from './sourceConsent'
import { sourceRegistration } from './sources/registry'

/**
 * The wizard's steps, one word each; the heading of a step says more. The last step shows every
 * answer for a check before the library is created. An upload library has no source step.
 */
export const STEP_SOURCE_TYPE = 'Quellart'
export const STEP_SOURCE_SETUP = 'Quelle'
export const STEP_NAME = 'Name'
export const STEP_SHARING = 'Freigaben'
export const STEP_REVIEW = 'Prüfen'

/** A private library has no sharing step: only its owner ever reads it. */
export function stepsFor(acceptsUploads: boolean, privateLibrary: boolean): string[] {
  if (acceptsUploads) return [STEP_SOURCE_TYPE, STEP_NAME, STEP_SHARING, STEP_REVIEW]
  return privateLibrary
    ? [STEP_SOURCE_TYPE, STEP_SOURCE_SETUP, STEP_NAME, STEP_REVIEW]
    : [STEP_SOURCE_TYPE, STEP_SOURCE_SETUP, STEP_NAME, STEP_SHARING, STEP_REVIEW]
}

export const stepHeadings: Record<string, string> = {
  [STEP_SOURCE_TYPE]: 'Woher kommt das Wissen?',
  [STEP_SOURCE_SETUP]: 'Quelle einrichten',
  [STEP_NAME]: 'Name & Beschreibung',
  [STEP_SHARING]: 'Freigaben',
  [STEP_REVIEW]: 'Prüfen',
}

export const CONSENT_REQUIRED = 'Bitte verbinden Sie zuerst die Quelle.'
export const NOTHING_CREATABLE =
  'Sie dürfen derzeit keine Bibliothek anlegen. Wenden Sie sich an die Systemverwaltung, wenn Sie es benötigen.'
export const PENDING_CONNECTION_UNUSABLE =
  'Die Zustimmung beim Anbieter ist abgelaufen oder schon verwendet. Bitte verbinden Sie die Quelle erneut.'

export type DraftIntent = Extract<SourceConsentIntent, { purpose: 'LIBRARY_NEW' }>

/** The draft the provider returned to; the wizard forgets it as soon as it has mounted. */
export function restoredDraft(): DraftIntent | null {
  const intent = readConsentIntent()
  return intent?.purpose === 'LIBRARY_NEW' ? intent : null
}

/** The entered values of a restored draft under the key its form keeps them. */
export function restoredSourceValues(intent: DraftIntent | null): Record<SourceTypeKey, unknown> {
  if (!intent) return {}
  const type = intent.draft.sourceType
  const configuration = sourceRegistration(type)?.configuration
  if (!configuration) return {}
  return {
    [configuration.valuesKey ?? type]: { ...configuration.empty, ...intent.draft.sourceValues },
  }
}
