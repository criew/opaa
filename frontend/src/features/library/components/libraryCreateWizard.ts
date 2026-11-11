import type { SourceTypeKey } from '@/shared/api/types'
import { STEP_MASTER_DATA, STEP_REVIEW, type WizardSteps } from '@/shared/ui/wizard/wizardSteps'
import { readConsentIntent, type SourceConsentIntent } from './sourceConsent'
import { sourceRegistration } from './sources/registry'

/**
 * The wizard's steps, one word each (guidelines 5.15): the choice of type, the master data, the
 * source, the sharing and a last check of every answer before the library is created. An upload
 * library has no source step, a private one no sharing step: only its owner ever reads it.
 */
export const STEP_SOURCE_TYPE = 'Quellart'
export const STEP_SOURCE_SETUP = 'Quelle'
export const STEP_SHARING = 'Freigaben'
export { STEP_MASTER_DATA, STEP_REVIEW }

export function stepsFor(acceptsUploads: boolean, privateLibrary: boolean): WizardSteps {
  if (acceptsUploads) return [STEP_SOURCE_TYPE, STEP_MASTER_DATA, STEP_SHARING, STEP_REVIEW]
  return privateLibrary
    ? [STEP_SOURCE_TYPE, STEP_MASTER_DATA, STEP_SOURCE_SETUP, STEP_REVIEW]
    : [STEP_SOURCE_TYPE, STEP_MASTER_DATA, STEP_SOURCE_SETUP, STEP_SHARING, STEP_REVIEW]
}

/** Where the wizard resumes after the consent at the provider: the source step of a connector. */
export const SOURCE_SETUP_INDEX = stepsFor(false, false).indexOf(STEP_SOURCE_SETUP)

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
