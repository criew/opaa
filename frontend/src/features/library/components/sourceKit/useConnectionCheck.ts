import { useState } from 'react'
import type { SourceConnectionTestRequest, SourceConnectionTestResponse } from '@/shared/api/types'
import { testLibrarySource } from '../../api/libraryApi'

type StageState = 'ok' | 'failed'

interface Stage {
  name: 'Adresse' | 'Anmeldung' | 'Rechte'
  state: StageState
}

interface Outcome {
  /** The request the outcome was measured with; a since-changed one hides it (Prüfliste #33). */
  token: string
  stages: Stage[]
  message: string
  /** Objects of the selection the source refused, by name or key. */
  refused: string[]
  result?: SourceConnectionTestResponse
}

/** What the source refused per object, from the connector's `details` (S3, Drive, SharePoint, folders, spaces). */
function refusedOf(details: Record<string, unknown> | null | undefined): string[] {
  if (!details) return []
  const refused: string[] = []
  for (const value of Object.values(details)) {
    if (!Array.isArray(value)) continue
    for (const item of value) {
      if (typeof item === 'string') {
        refused.push(item)
        continue
      }
      if (!item || typeof item !== 'object') continue
      const entry = item as Record<string, unknown>
      const failed =
        entry.reachable === false || entry.listAllowed === false || entry.readable === false
      if (!failed) continue
      const name = [
        entry.bucket,
        entry.prefix,
        entry.scope,
        entry.driveId,
        entry.folder,
        entry.path,
        entry.key,
      ]
        .filter((part) => typeof part === 'string' && part !== '')
        .join('/')
      refused.push(entry.message ? `${name}: ${String(entry.message)}` : name)
    }
  }
  return refused
}

function outcomeOf(token: string, result: SourceConnectionTestResponse): Outcome {
  const stages: Stage[] = [{ name: 'Adresse', state: 'ok' }]
  if (!result.credentialsVerified && !result.reachable) {
    stages.push({ name: 'Anmeldung', state: 'failed' })
  } else {
    if (result.credentialsVerified) stages.push({ name: 'Anmeldung', state: 'ok' })
    stages.push({ name: 'Rechte', state: result.reachable ? 'ok' : 'failed' })
  }
  return { token, stages, message: result.message, refused: refusedOf(result.details), result }
}

/**
 * The one connection test of every source form (Variante E): run by its own button and
 * automatically before „Weiter“ or „Speichern“. The result names the stage that failed - Adresse,
 * Anmeldung or Rechte - and belongs to the request it was measured with: a changed field hides it.
 */
export function useConnectionCheck(request: SourceConnectionTestRequest | null) {
  const token = JSON.stringify(request)
  const [outcome, setOutcome] = useState<Outcome | null>(null)
  const [running, setRunning] = useState(false)
  const [waived, setWaived] = useState<string | null>(null)
  const visible = outcome?.token === token ? outcome : null
  const passed = visible !== null && visible.stages.every((stage) => stage.state === 'ok')

  async function run(): Promise<{ ok: boolean; result?: SourceConnectionTestResponse }> {
    if (!request) return { ok: false }
    setRunning(true)
    try {
      const result = await testLibrarySource(request)
      const next = outcomeOf(token, result)
      setOutcome(next)
      return { ok: next.stages.every((stage) => stage.state === 'ok'), result }
    } catch (err) {
      setOutcome({
        token,
        stages: [{ name: 'Adresse', state: 'failed' }],
        message: err instanceof Error ? err.message : 'Die Quelle antwortet nicht.',
        refused: [],
      })
      return { ok: false }
    } finally {
      setRunning(false)
    }
  }

  return {
    available: request !== null,
    running,
    outcome: visible,
    passed,
    /** The person chose to go on although the test failed for exactly this request. */
    waived: waived === token,
    waive: () => setWaived(token),
    run,
  }
}

export type ConnectionCheckState = ReturnType<typeof useConnectionCheck>
