import type { Ref } from 'react'
import Box from '@mui/material/Box'
import type { ConnectionProfileOptionsState } from '@/features/connection'
import type {
  ConnectionProfileOption,
  PendingSourceConnection,
  SourceTypeDescriptor,
} from '@/shared/api/types'
import type { ConfluenceFullSyncRhythm, LibraryScheduleValues } from '../utils/librarySchedule'
import ConnectionProfileSelect from './ConnectionProfileSelect'
import { onlyPersons } from './connectionChoice'
import SourceConsentPanel from './SourceConsentPanel'
import SourceSetupRows, { type SourceSetupHandle } from './setup/SourceSetupRows'
import type { SetupRowId, SourceSetupSpec } from './setup/setupTypes'
import type { SourceFormContext } from './sources/types'

interface CreateSourceSetupStepProps {
  showConnectionSelect: boolean
  descriptor: SourceTypeDescriptor | undefined
  profileOptions: ConnectionProfileOptionsState
  connectionChoice: string | null
  onChooseConnection: (next: string) => void
  /** Leads the summary of „Verbindung“: the chosen Zugang. */
  connectionLead: string | null
  /** The profile whose source needs the provider's consent before the form shows. */
  consentProfile: ConnectionProfileOption | undefined
  pendingConnection: PendingSourceConnection | null
  serviceAccountConfirmed: boolean
  onServiceAccountConfirmedChange: (checked: boolean) => void
  confirmationMissing: boolean
  connectingSource: boolean
  onConnectSource: () => void
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  setup: SourceSetupSpec<any>
  awaitsConsent: boolean
  setupRef: Ref<SourceSetupHandle>
  initialRow: SetupRowId | null
  values: unknown
  formContext: SourceFormContext
  onSourceChange: (patch: object) => void
  schedule: LibraryScheduleValues
  onScheduleChange: (patch: Partial<LibraryScheduleValues>) => void
  confluenceRhythm: ConfluenceFullSyncRhythm | undefined
  startFirstRun: boolean
  onStartFirstRunChange: (checked: boolean) => void
  firstRunHint: string
  /** How many ways of connection there are to choose from. */
  connectionChoices: number
}

/**
 * The step „Quelle einrichten" as summary rows: Verbindung (with the way of connection and the
 * consent at the provider), Umfang and Zeitplan; the first row that needs input is open.
 */
export default function CreateSourceSetupStep({
  showConnectionSelect,
  descriptor,
  profileOptions,
  connectionChoice,
  onChooseConnection,
  connectionLead,
  consentProfile,
  pendingConnection,
  serviceAccountConfirmed,
  onServiceAccountConfirmedChange,
  confirmationMissing,
  connectingSource,
  onConnectSource,
  setup,
  awaitsConsent,
  setupRef,
  initialRow,
  values,
  formContext,
  onSourceChange,
  schedule,
  onScheduleChange,
  confluenceRhythm,
  startFirstRun,
  onStartFirstRunChange,
  firstRunHint,
  connectionChoices,
}: CreateSourceSetupStepProps) {
  const prelude =
    (showConnectionSelect && descriptor) || consentProfile ? (
      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2.5 }}>
        {showConnectionSelect && descriptor && (
          <ConnectionProfileSelect
            descriptor={descriptor}
            state={profileOptions}
            value={connectionChoice}
            onChange={onChooseConnection}
            offerOwnAddress
            offerPrivate
            idPrefix="library-create"
          />
        )}
        {consentProfile && (
          <SourceConsentPanel
            profileName={consentProfile.name}
            pending={pendingConnection}
            confirmed={serviceAccountConfirmed}
            onConfirmedChange={onServiceAccountConfirmedChange}
            confirmationMissing={confirmationMissing}
            busy={connectingSource}
            onConnect={onConnectSource}
          />
        )}
      </Box>
    ) : undefined
  // without a way of connection or before the consent, the connection fields have nothing to sign in with
  const ready = connectionChoice !== null && !awaitsConsent
  const shownSetup = ready
    ? setup
    : { ...setup, sections: setup.sections.filter((s) => s.id !== 'connection'), probe: undefined }
  return (
    <Box>
      <SourceSetupRows
        // a new way of connection starts the rows afresh, so no test result outlives it
        key={connectionChoice ?? 'none'}
        ref={setupRef}
        mode="create"
        spec={shownSetup}
        values={values}
        onChange={onSourceChange}
        context={formContext}
        schedule={schedule}
        onScheduleChange={onScheduleChange}
        rhythm={confluenceRhythm}
        firstRun={{ value: startFirstRun, onChange: onStartFirstRunChange, hint: firstRunHint }}
        connectionPrelude={prelude}
        connectionLead={connectionLead}
        // nothing to fill in under „Verbindung“: the first open row is „Umfang“ - unless the one
        // way was taken for the person and is private, whose consequence the open row shows
        initialRow={
          initialRow === 'connection' &&
          setup.quietConnection &&
          connectionChoices === 1 &&
          connectionChoice !== null &&
          !awaitsConsent &&
          !(formContext.privateLibrary && descriptor && onlyPersons(descriptor))
            ? 'scope'
            : initialRow
        }
      />
    </Box>
  )
}
