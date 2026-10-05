import type {
  AssetOwnerType,
  ConnectionProfileOption,
  GroupListResponse,
  PendingSourceConnection,
  SourceTypeKey,
} from '../../types/api'
import type { LibraryScheduleValues } from '../../utils/librarySchedule'
import type { PendingGrant } from '../assets/pendingGrants'

/** The confirmation every consent for a library's source requires before it starts. */
export const SERVICE_ACCOUNT_CONFIRMATION = 'Ich verbinde ein Dienstkonto, kein persönliches Konto'

export const SERVICE_ACCOUNT_MISSING = 'Bitte bestätigen Sie, dass Sie ein Dienstkonto verbinden.'

export const CONSENT_INTENT_STORAGE_KEY = 'opaa.sourceConsent'

/**
 * Whether a shared library on `option` connects its source by a consent of its own ("Quelle
 * verbinden"): the profile signs in by OAuth and admits libraries.
 */
export function connectsSource(
  option: ConnectionProfileOption | undefined,
  privateLibrary: boolean,
): boolean {
  return (
    option !== undefined &&
    !privateLibrary &&
    option.authMethod === 'OAUTH' &&
    option.ownership !== 'PERSON'
  )
}

/** The wizard's entries as they survive the trip to the provider; never a secret. */
export interface WizardDraft {
  sourceType: SourceTypeKey
  chosenConnections: Record<string, string>
  sourceValues: Record<string, unknown>
  schedule: LibraryScheduleValues
  startFirstRun: boolean
  name: string
  nameTouched: boolean
  description: string
  ownerType: AssetOwnerType
  selectedGroup: GroupListResponse | null
  pendingGrants: PendingGrant[]
  responsibleIsGroup: boolean
}

/** What a consent under way returns to, kept in this tab's sessionStorage. */
export type SourceConsentIntent =
  | {
      purpose: 'LIBRARY_NEW'
      profileId: string
      draft: WizardDraft
      pending?: PendingSourceConnection
    }
  | { purpose: 'LIBRARY_RECONNECT'; profileId: string; libraryId: string }

/**
 * The entered `values` a draft may keep: only the `fields` the source form lists as such
 * (SourceConfiguration.draftFields), so a field added later stays out until it is listed.
 */
export function draftValues(values: unknown, fields: readonly string[]): Record<string, unknown> {
  if (values === null || typeof values !== 'object') return {}
  return Object.fromEntries(Object.entries(values).filter(([key]) => fields.includes(key)))
}

export function rememberConsentIntent(intent: SourceConsentIntent): void {
  try {
    sessionStorage.setItem(CONSENT_INTENT_STORAGE_KEY, JSON.stringify(intent))
  } catch {
    // without storage the wizard starts afresh after the return
  }
}

export function readConsentIntent(): SourceConsentIntent | null {
  try {
    const stored = sessionStorage.getItem(CONSENT_INTENT_STORAGE_KEY)
    if (!stored) return null
    const intent = JSON.parse(stored) as SourceConsentIntent
    return intent?.purpose === 'LIBRARY_NEW' || intent?.purpose === 'LIBRARY_RECONNECT'
      ? intent
      : null
  } catch {
    return null
  }
}

export function forgetConsentIntent(): void {
  try {
    sessionStorage.removeItem(CONSENT_INTENT_STORAGE_KEY)
  } catch {
    // nothing stored then
  }
}

/**
 * Hands `pending` to the wizard waiting for a consent on `profileId`; `false` when none waits, so
 * the connection is not given to a draft it was not started from.
 */
export function attachPendingConnection(
  profileId: string,
  pending: PendingSourceConnection,
): boolean {
  const intent = readConsentIntent()
  if (intent?.purpose !== 'LIBRARY_NEW' || intent.profileId !== profileId) return false
  rememberConsentIntent({ ...intent, pending })
  return true
}
