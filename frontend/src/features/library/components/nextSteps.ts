import { useEffect, useState } from 'react'
import type { SourceTypeKey } from '@/shared/api/types'
import { listLibraryMetadataFields } from '../api/libraryMetadataApi'
import type { LibraryDetailTab } from './libraryDetailAccess'

/** One step the wizard cannot take because it needs the created library (inventory, ch. 8). */
export interface NextStep {
  key: 'source' | 'space' | 'upload' | 'live' | 'metadata'
  label: string
  done: boolean
  tab: LibraryDetailTab
  /** The row of the Reiter „Quelle“ the step opens, if any. */
  row?: string
}

const STORAGE_PREFIX = 'opaa-next-steps:'

type StoredState = 'open' | 'dismissed'

// A dismissed list stays away for good (localStorage); an open one belongs to the visit after
// the wizard and lives in the tab only (sessionStorage), never on the library itself.
function storageFor(value: StoredState): Storage {
  return value === 'dismissed' ? localStorage : sessionStorage
}

function readStored(libraryId: string): StoredState | null {
  try {
    if (localStorage.getItem(STORAGE_PREFIX + libraryId) === 'dismissed') return 'dismissed'
    return sessionStorage.getItem(STORAGE_PREFIX + libraryId) === 'open' ? 'open' : null
  } catch {
    return null
  }
}

function writeStored(libraryId: string, value: StoredState) {
  try {
    storageFor(value).setItem(STORAGE_PREFIX + libraryId, value)
  } catch {
    // without storage the list lasts as long as the address carries `justCreated`
  }
}

interface NextStepsInput {
  libraryId: string
  sourceType: SourceTypeKey | null
  /** The address carries `justCreated=1`: the wizard has just created the library. */
  justCreated: boolean
  /** Whoever may change the library; a reader gets no steps. */
  mayChange: boolean
  documentCount: number
  spaceCount: number | null | undefined
  pushSecretSet: boolean
  /** The source answers: no block, no refused sign-in on the newest run. */
  sourceConnected: boolean
  metadataEnabled: boolean
}

/**
 * The steps after creating a library: shown once the wizard hands over with `justCreated`, until
 * every step is done or the list is dismissed - a dismissed list never comes back.
 */
export function useNextSteps(input: NextStepsInput): {
  steps: NextStep[]
  dismiss: () => void
} {
  const { libraryId, justCreated } = input
  const [stored, setStored] = useState<StoredState | null>(() => readStored(libraryId))
  const [fieldCount, setFieldCount] = useState<number | null>(null)

  useEffect(() => {
    if (justCreated && readStored(libraryId) !== 'dismissed') writeStored(libraryId, 'open')
  }, [justCreated, libraryId])

  const active = input.mayChange && stored !== 'dismissed' && (justCreated || stored === 'open')

  useEffect(() => {
    if (!active || !input.metadataEnabled) return
    let cancelled = false
    listLibraryMetadataFields(libraryId)
      .then((response) => {
        if (!cancelled) setFieldCount(response.items.length)
      })
      .catch(() => {
        if (!cancelled) setFieldCount(0)
      })
    return () => {
      cancelled = true
    }
  }, [active, input.metadataEnabled, libraryId])

  if (!active || input.sourceType == null) return { steps: [], dismiss: () => {} }

  const steps: NextStep[] = [
    {
      key: 'space',
      label: 'In Space verwenden',
      done: (input.spaceCount ?? 0) > 0,
      tab: 'zuordnungen',
    },
  ]
  // the wizard's own step, ticked: the list starts from what is already done
  if (input.sourceType !== 'UPLOAD') {
    steps.unshift(
      input.sourceConnected
        ? { key: 'source', label: 'Quelle verbunden', done: true, tab: 'quelle' }
        : { key: 'source', label: 'Zugang prüfen', done: false, tab: 'quelle', row: 'connection' },
    )
  }
  if (input.sourceType === 'UPLOAD') {
    steps.unshift({
      key: 'upload',
      label: 'Dateien hochladen',
      done: input.documentCount > 0,
      tab: 'dokumente',
    })
  }
  if (input.sourceType === 'CONFLUENCE' || input.sourceType === 'S3') {
    steps.push({
      key: 'live',
      label: 'Live-Aktualisierung einrichten',
      done: input.pushSecretSet,
      tab: 'quelle',
      row: 'live',
    })
  }
  if (input.metadataEnabled) {
    steps.push({
      key: 'metadata',
      label: 'Feld anlegen',
      done: (fieldCount ?? 0) > 0,
      tab: 'metadaten',
    })
  }
  const dismiss = () => {
    writeStored(libraryId, 'dismissed')
    setStored('dismissed')
  }
  if (steps.every((step) => step.done)) return { steps: [], dismiss }
  return { steps, dismiss }
}
