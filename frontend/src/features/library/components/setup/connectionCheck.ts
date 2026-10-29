import { useCallback, useEffect, useRef, useState } from 'react'
import type { SourceConnectionTestRequest, SourceConnectionTestResponse } from '@/shared/api/types'
import { testLibrarySource } from '../../api/libraryApi'
import type { CheckState } from './setupTypes'
import { unreadableScopeNames } from '../scopeMarks'

export type StageState = 'ok' | 'failed' | 'skipped' | 'open'

export interface CheckStage {
  label: 'Adresse' | 'Anmeldung' | 'Rechte'
  state: StageState
  text: string
}

export interface CheckOutcome {
  /** The request this outcome was measured with; a changed one makes it stale. */
  token: string
  state: Exclude<CheckState, 'unchecked' | 'checking'>
  stages: CheckStage[]
  message: string
  /** Scopes the source could not read, by name, for a partial result. */
  unreadable: string[]
}

type ProbedSelection = Pick<SourceConnectionTestRequest, 'sourceType' | 'sourceSettings'>

/**
 * The scope entries of a connector's findings that failed. Entries the backend reports by key
 * (Google Drive scopes, SharePoint drives, Confluence spaces) are named from the probed selection
 * the way the Umfang shows them, never by their raw key.
 */
function unreadableOf(
  details: Record<string, unknown> | null | undefined,
  probed: ProbedSelection | null,
): { failed: string[]; total: number } {
  const { failed, total } = reportedUnreadable(details)
  if (!probed) return { failed: failed.map((entry) => entry.name), total }
  const names = unreadableScopeNames(
    { sourceType: probed.sourceType, sourceSettings: probed.sourceSettings ?? {} },
    failed.flatMap((entry) => (entry.key === undefined ? [] : [entry.key])),
  )
  let next = 0
  return {
    failed: failed.map((entry) => (entry.key === undefined ? entry.name : names[next++])),
    total,
  }
}

/** The failed entries as the backend reports them: a key where it names one, else a name. */
function reportedUnreadable(details: Record<string, unknown> | null | undefined): {
  failed: Array<{ key?: string; name: string }>
  total: number
} {
  if (!details) return { failed: [], total: 0 }
  const failed: Array<{ key?: string; name: string }> = []
  let total = 0
  for (const key of ['scopes', 'libraries', 'folders']) {
    const list = details[key]
    if (!Array.isArray(list)) continue
    for (const raw of list) {
      const entry = raw as Record<string, unknown>
      total += 1
      const ok =
        entry.reachable !== false &&
        entry.bucketReachable !== false &&
        entry.listAllowed !== false &&
        entry.readAllowed !== false
      if (!ok) {
        const name =
          entry.name ?? entry.folder ?? entry.scope ?? entry.driveId ?? entry.bucket ?? '?'
        const prefix = typeof entry.prefix === 'string' && entry.prefix ? `/${entry.prefix}` : ''
        const scopeKey = entry.name == null ? (entry.scope ?? entry.driveId) : undefined
        failed.push({
          ...(typeof scopeKey === 'string' ? { key: scopeKey } : {}),
          name: `${String(name)}${prefix}`,
        })
      }
    }
  }
  const spaces = details.unreadableSpaces
  if (Array.isArray(spaces)) {
    failed.push(...spaces.map((space) => ({ key: String(space), name: String(space) })))
  }
  return { failed, total }
}

const NOT_CHECKED = { state: 'open', text: 'nicht geprüft' } as const

/**
 * The staged reading of one test result: the address answered, the sign-in was accepted, the
 * chosen scope can be read. The first stage that failed is the one the person has to fix. A
 * source at a fixed address has no address stage: what it cannot reach, it may not sign in to or
 * read. `probed` is the selection the test ran with; it names the unreadable scopes.
 */
export function outcomeOf(
  token: string,
  result: SourceConnectionTestResponse,
  signsIn: boolean,
  fixedAddress = false,
  probed: ProbedSelection | null = null,
): CheckOutcome {
  const { failed, total } = unreadableOf(result.details, probed)
  const address: CheckStage[] = fixedAddress
    ? []
    : [{ label: 'Adresse', state: 'ok', text: 'erreichbar' }]
  const signIn: CheckStage = signsIn
    ? { label: 'Anmeldung', state: 'ok', text: 'angenommen' }
    : { label: 'Anmeldung', state: 'skipped', text: 'entfällt' }
  const unreadable: CheckStage = {
    label: 'Rechte',
    state: 'failed',
    text:
      failed.length > 0
        ? `${failed.length} von ${Math.max(total, failed.length)} nicht lesbar`
        : 'nicht lesbar',
  }
  const outcome = (state: CheckOutcome['state'], stages: CheckStage[]): CheckOutcome => ({
    token,
    state,
    stages,
    message: result.message,
    unreadable: failed,
  })
  const rightsOpen: CheckStage = { label: 'Rechte', ...NOT_CHECKED }
  if (result.reachable) {
    // a reached source that did not verify the secret has not passed: the sign-in and the rights
    // behind it are still open
    if (signsIn && result.credentialsVerified === false) {
      return outcome('failed', [...address, { label: 'Anmeldung', ...NOT_CHECKED }, rightsOpen])
    }
    return failed.length > 0
      ? outcome('partial', [...address, signIn, unreadable])
      : outcome('passed', [...address, signIn, { label: 'Rechte', state: 'ok', text: 'lesbar' }])
  }
  if (signsIn && result.credentialsVerified === false) {
    return outcome('failed', [
      ...address,
      { label: 'Anmeldung', state: 'failed', text: 'abgelehnt' },
      rightsOpen,
    ])
  }
  if (fixedAddress) {
    // only an accepted sign-in tells the rights apart; a refusal before it (no scope chosen, no
    // account) names no stage, the message says what is missing
    return outcome(
      'failed',
      result.credentialsVerified === true
        ? [signIn, unreadable]
        : [signsIn ? { label: 'Anmeldung', ...NOT_CHECKED } : signIn, rightsOpen],
    )
  }
  // an accepted sign-in presupposes a reached address; only the rights can be missing
  if (signsIn && result.credentialsVerified === true) {
    return outcome('failed', [...address, signIn, unreadable])
  }
  return outcome('failed', [
    { label: 'Adresse', state: 'failed', text: 'nicht erreichbar' },
    signsIn ? { label: 'Anmeldung', ...NOT_CHECKED } : signIn,
    rightsOpen,
  ])
}

function failedOutcome(
  token: string,
  message: string,
  signsIn: boolean,
  fixedAddress: boolean,
): CheckOutcome {
  const rightsOpen: CheckStage = { label: 'Rechte', ...NOT_CHECKED }
  return {
    token,
    state: 'failed',
    // the test itself did not answer: no stage of a fixed address can be blamed
    stages: fixedAddress
      ? [
          signsIn
            ? { label: 'Anmeldung', ...NOT_CHECKED }
            : { label: 'Anmeldung', state: 'skipped', text: 'entfällt' },
          rightsOpen,
        ]
      : [
          { label: 'Adresse', state: 'failed', text: 'nicht erreichbar' },
          signsIn
            ? { label: 'Anmeldung', ...NOT_CHECKED }
            : { label: 'Anmeldung', state: 'skipped', text: 'entfällt' },
          rightsOpen,
        ],
    message,
    unreadable: [],
  }
}

export interface ConnectionCheck {
  state: CheckState
  outcome: CheckOutcome | null
  /** Runs the test for the current request; resolves with the state it reached. */
  run: () => Promise<CheckState>
  /** The person chose to go on despite a failed test of exactly this request. */
  accepted: boolean
  accept: () => void
}

/**
 * The connection check of a setup. Its result belongs to the request it was measured with: a
 * changed connection field makes the request differ, and the stale result disappears.
 */
export function useConnectionCheck(
  request: SourceConnectionTestRequest | null,
  signsIn: boolean,
  fixedAddress = false,
): ConnectionCheck {
  const token = request ? JSON.stringify(request) : ''
  const [outcome, setOutcome] = useState<CheckOutcome | null>(null)
  const [running, setRunning] = useState<string | null>(null)
  const [acceptedToken, setAcceptedToken] = useState<string | null>(null)
  const latest = useRef({ request, token })
  useEffect(() => {
    latest.current = { request, token }
  })

  const run = useCallback(async (): Promise<CheckState> => {
    const { request: current, token: mine } = latest.current
    if (!current) return 'unchecked'
    setRunning(mine)
    let next: CheckOutcome
    try {
      next = outcomeOf(mine, await testLibrarySource(current), signsIn, fixedAddress, current)
    } catch (err) {
      next = failedOutcome(
        mine,
        err instanceof Error && err.message
          ? err.message
          : 'Die Verbindung ließ sich nicht prüfen.',
        signsIn,
        fixedAddress,
      )
    }
    setRunning((r) => (r === mine ? null : r))
    if (latest.current.token !== mine) return 'unchecked'
    setOutcome(next)
    return next.state
  }, [signsIn, fixedAddress])

  const visible = outcome?.token === token ? outcome : null
  const state: CheckState =
    running === token && token !== '' ? 'checking' : (visible?.state ?? 'unchecked')
  return {
    state,
    outcome: visible,
    run,
    accepted: acceptedToken === token && token !== '',
    accept: () => setAcceptedToken(token),
  }
}
