import { useCallback, useEffect, useRef, useState } from 'react'
import type { SourceConnectionTestRequest, SourceConnectionTestResponse } from '@/shared/api/types'
import { testLibrarySource } from '../../api/libraryApi'
import type { CheckState } from './setupTypes'

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

/** The scope entries of a connector's findings that failed, by the names the backend gives them. */
function unreadableOf(details: Record<string, unknown> | null | undefined): {
  failed: string[]
  total: number
} {
  if (!details) return { failed: [], total: 0 }
  const failed: string[] = []
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
        failed.push(`${String(name)}${prefix}`)
      }
    }
  }
  const spaces = details.unreadableSpaces
  if (Array.isArray(spaces)) failed.push(...spaces.map(String))
  return { failed, total }
}

/**
 * The staged reading of one test result: the address answered, the sign-in was accepted, the
 * chosen scope can be read. The first stage that failed is the one the person has to fix.
 */
export function outcomeOf(
  token: string,
  result: SourceConnectionTestResponse,
  signsIn: boolean,
): CheckOutcome {
  const { failed, total } = unreadableOf(result.details)
  const signIn: CheckStage = signsIn
    ? { label: 'Anmeldung', state: 'ok', text: 'angenommen' }
    : { label: 'Anmeldung', state: 'skipped', text: 'entfällt' }
  if (result.reachable) {
    const rights: CheckStage =
      failed.length > 0
        ? {
            label: 'Rechte',
            state: 'failed',
            text: `${failed.length} von ${Math.max(total, failed.length)} nicht lesbar`,
          }
        : { label: 'Rechte', state: 'ok', text: 'lesbar' }
    return {
      token,
      state: failed.length > 0 ? 'partial' : 'passed',
      stages: [{ label: 'Adresse', state: 'ok', text: 'erreichbar' }, signIn, rights],
      message: result.message,
      unreadable: failed,
    }
  }
  const refusedSignIn = signsIn && result.credentialsVerified === false
  return {
    token,
    state: 'failed',
    stages: refusedSignIn
      ? [
          { label: 'Adresse', state: 'ok', text: 'erreichbar' },
          { label: 'Anmeldung', state: 'failed', text: 'abgelehnt' },
          { label: 'Rechte', state: 'open', text: 'nicht geprüft' },
        ]
      : [
          { label: 'Adresse', state: 'failed', text: 'nicht erreichbar' },
          { ...signIn, state: signsIn ? 'open' : 'skipped', text: signsIn ? 'nicht geprüft' : 'entfällt' },
          { label: 'Rechte', state: 'open', text: 'nicht geprüft' },
        ],
    message: result.message,
    unreadable: failed,
  }
}

function failedOutcome(token: string, message: string, signsIn: boolean): CheckOutcome {
  return {
    token,
    state: 'failed',
    stages: [
      { label: 'Adresse', state: 'failed', text: 'nicht erreichbar' },
      {
        label: 'Anmeldung',
        state: signsIn ? 'open' : 'skipped',
        text: signsIn ? 'nicht geprüft' : 'entfällt',
      },
      { label: 'Rechte', state: 'open', text: 'nicht geprüft' },
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
      next = outcomeOf(mine, await testLibrarySource(current), signsIn)
    } catch (err) {
      next = failedOutcome(
        mine,
        err instanceof Error && err.message ? err.message : 'Die Verbindung ließ sich nicht prüfen.',
        signsIn,
      )
    }
    setRunning((r) => (r === mine ? null : r))
    if (latest.current.token !== mine) return 'unchecked'
    setOutcome(next)
    return next.state
  }, [signsIn])

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
