import { useState, type ReactNode, type Ref } from 'react'
import type { ConnectionProfileRef, LibrarySchedule, SourceTypeKey } from '@/shared/api/types'
import { notify } from '@/shared/stores/notificationStore'
import { useLibraryStore } from '../stores/libraryStore'
import {
  scheduleUpdateFrom,
  scheduleValuesFrom,
  type ConfluenceFullSyncRhythm,
} from '../utils/librarySchedule'
import { sourceRegistration } from './sources/registry'
import {
  connectionFields,
  payloadUnder,
  sourceConnectionOfRef,
  withConnection,
  withoutFixed,
} from './sources/sourceConnection'
import type { SourceFormContext } from './sources/types'
import SourceSetupRows, { type SourceSetupHandle } from './setup/SourceSetupRows'
import type { RowStatus, SetupRowId } from './setup/setupTypes'

export interface SourceRowsLibrary {
  name: string
  description?: string | null
  sourceType: SourceTypeKey
  sourcePath?: string | null
  sourceUrl?: string | null
  sourceCredentialsSet?: boolean | null
  sourceSettings?: Record<string, unknown> | null
  fullSyncIntervalDefaultDays?: number | null
  schedule?: LibrarySchedule | null
  connectionProfile?: ConnectionProfileRef | null
  privateLibrary?: boolean
}

const SAVED: Record<SetupRowId, string> = {
  connection: 'Verbindung gespeichert. Sie gilt ab dem nächsten Lauf.',
  scope: 'Umfang gespeichert. Er gilt ab dem nächsten Lauf.',
  schedule: 'Zeitplan gespeichert.',
}

/** The schedule update with its settings part laid over `base`, when there is one. */
function withSettingsBase<T extends { sourceSettings?: Record<string, unknown> }>(
  update: T,
  base: Record<string, unknown> | null | undefined,
): T {
  return base && update.sourceSettings
    ? { ...update, sourceSettings: { ...base, ...update.sourceSettings } }
    : update
}

/**
 * The Reiter „Quelle“ as summary rows: Verbindung, Umfang and Zeitplan, each with „Ändern“, which
 * opens its fields in place and saves the row alone. A reader sees the Umfang row without „Ändern“.
 * Mounted afresh whenever the stored library changes, so the rows always start from it.
 */
export default function LibrarySourceRows({
  libraryId,
  library,
  canEdit,
  connectionPrelude,
  connectionStatus = null,
  fixedScope = null,
  unreadable = [],
  ref,
}: {
  libraryId: string
  library: SourceRowsLibrary
  canEdit: boolean
  /** Inside „Verbindung“: the Zugang and the source connection with their own actions. */
  connectionPrelude?: ReactNode
  /** The connection's last known state (from the newest run). */
  connectionStatus?: RowStatus | null
  /** The Umfang of a source without a selection. */
  fixedScope?: string | null
  /** Names of the Umfang entries the last run could not read. */
  unreadable?: string[]
  ref?: Ref<SourceSetupHandle>
}) {
  const configuration = sourceRegistration(library.sourceType)?.configuration ?? null
  const updateExistingLibrary = useLibraryStore((s) => s.updateExistingLibrary)
  const profile = library.connectionProfile ?? null
  const connection = (profile && sourceConnectionOfRef(profile)) ?? undefined
  const context: SourceFormContext = {
    mode: 'edit',
    sourceType: library.sourceType,
    idPrefix: 'edit-source',
    libraryId,
    credentialsStored: Boolean(library.sourceCredentialsSet),
    originalSourceUrl: library.sourceUrl,
    connection,
    privateLibrary: library.privateLibrary,
  }
  const rhythm: ConfluenceFullSyncRhythm | undefined = configuration?.fullSyncRhythm
    ? {
        intervalDays:
          (library.sourceSettings?.fullSyncIntervalDays as number | null | undefined) ?? null,
        defaultDays: library.fullSyncIntervalDefaultDays ?? null,
      }
    : undefined
  const [stored] = useState<unknown>(() => configuration?.fromLibrary(library))
  const [storedSchedule] = useState(() => scheduleValuesFrom(library.schedule, rhythm))
  const [values, setValues] = useState<unknown>(stored)
  const [schedule, setSchedule] = useState(storedSchedule)
  const setup = configuration?.setup
  if (!configuration || !setup) return null
  const shown = withConnection(values, connection)

  async function save(row: SetupRowId) {
    if (!configuration) return
    const update =
      row === 'schedule'
        ? withSettingsBase(
            scheduleUpdateFrom(schedule, rhythm),
            configuration.rhythmSettingsBase?.(library),
          )
        : payloadUnder(configuration.toPayload(shown), connectionFields(context))
    // name and description are resent unchanged: the update overwrites both unconditionally
    await updateExistingLibrary(libraryId, {
      name: library.name,
      description: library.description ?? undefined,
      ...update,
    })
    notify(SAVED[row], 'success')
  }

  return (
    <SourceSetupRows
      ref={ref}
      mode="edit"
      spec={setup}
      values={shown}
      onChange={(patch: object) =>
        setValues((prev: unknown) => ({
          ...(withConnection(prev, connection) as object),
          ...withoutFixed(patch, connection),
        }))
      }
      context={context}
      schedule={schedule}
      onScheduleChange={(patch) => setSchedule((prev) => ({ ...prev, ...patch }))}
      rhythm={rhythm}
      connectionPrelude={connectionPrelude}
      connectionLead={profile ? `Zugang „${profile.name}“` : null}
      rows={canEdit ? ['connection', 'scope', 'schedule'] : ['scope']}
      canEdit={canEdit}
      headingLevel="h2"
      knownConnectionStatus={connectionStatus}
      fixedScope={fixedScope}
      unreadable={unreadable}
      onSave={save}
      onDiscard={(row) => (row === 'schedule' ? setSchedule(storedSchedule) : setValues(stored))}
      isDirty={(row) =>
        row === 'schedule'
          ? JSON.stringify(schedule) !== JSON.stringify(storedSchedule)
          : JSON.stringify(values) !== JSON.stringify(stored)
      }
    />
  )
}
