import type { Ref } from 'react'
import Box from '@mui/material/Box'
import type { ConnectionProfileOptionsState } from '@/features/connection'
import type {
  ConnectionProfileOption,
  PendingSourceConnection,
  SourceTypeDescriptor,
} from '@/shared/api/types'
import type { SourceIssue } from '../utils/sourceIssue'
import type { ConfluenceFullSyncRhythm, LibraryScheduleValues } from '../utils/librarySchedule'
import ConnectionProfileSelect from './ConnectionProfileSelect'
import ScheduleSetup from './setup/ScheduleSetup'
import { SetupContext, type RegisteredCheck } from './setup/setupContext'
import { SetupSection } from './setup/SourceSetupLayout'
import SourceConsentPanel from './SourceConsentPanel'
import { SourceIssueContext } from './sources/sourceIssueContext'
import type { SourceFormContext, SourceRegistration } from './sources/types'

interface CreateSourceSetupStepProps {
  showConnectionSelect: boolean
  descriptor: SourceTypeDescriptor | undefined
  profileOptions: ConnectionProfileOptionsState
  connectionChoice: string | null
  onChooseConnection: (next: string) => void
  /** The profile whose source needs the provider's consent before the form shows. */
  consentProfile: ConnectionProfileOption | undefined
  pendingConnection: PendingSourceConnection | null
  serviceAccountConfirmed: boolean
  onServiceAccountConfirmedChange: (checked: boolean) => void
  confirmationMissing: boolean
  connectingSource: boolean
  onConnectSource: () => void
  SourceForm: NonNullable<SourceRegistration['configuration']>['Form'] | undefined
  awaitsConsent: boolean
  sourceFormRef: Ref<HTMLDivElement>
  /** All issues of the last „Weiter“, shown at their fields. */
  issues: SourceIssue[]
  /** Whether the form sits on the shared frame and renders the schedule itself. */
  framed: boolean
  registerCheck: (check: RegisteredCheck | null) => void
  values: unknown
  formContext: SourceFormContext
  onSourceChange: (patch: object) => void
  schedule: LibraryScheduleValues
  onScheduleChange: (patch: Partial<LibraryScheduleValues>) => void
  confluenceRhythm: ConfluenceFullSyncRhythm | undefined
  startFirstRun: boolean
  onStartFirstRunChange: (checked: boolean) => void
  firstRunHint: string
}

/** The step „Quelle einrichten": way of connection, consent, source form, schedule, first run. */
export default function CreateSourceSetupStep({
  showConnectionSelect,
  descriptor,
  profileOptions,
  connectionChoice,
  onChooseConnection,
  consentProfile,
  pendingConnection,
  serviceAccountConfirmed,
  onServiceAccountConfirmedChange,
  confirmationMissing,
  connectingSource,
  onConnectSource,
  SourceForm,
  awaitsConsent,
  sourceFormRef,
  issues,
  framed,
  registerCheck,
  values,
  formContext,
  onSourceChange,
  schedule,
  onScheduleChange,
  confluenceRhythm,
  startFirstRun,
  onStartFirstRunChange,
  firstRunHint,
}: CreateSourceSetupStepProps) {
  const scheduleSection = (
    <ScheduleSetup
      idPrefix="library-create-schedule"
      values={schedule}
      onChange={onScheduleChange}
      rhythm={confluenceRhythm}
      firstRun={{ checked: startFirstRun, onChange: onStartFirstRunChange, hint: firstRunHint }}
    />
  )
  const showForm = SourceForm && connectionChoice !== null && !awaitsConsent
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 4, maxWidth: 640 }}>
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
      <SetupContext
        value={{
          idPrefix: 'library-create',
          issues,
          schedule: framed ? scheduleSection : undefined,
          registerCheck,
        }}
      >
        {showForm && (
          <Box ref={sourceFormRef}>
            <SourceIssueContext value={issues[0] ?? null}>
              <SourceForm
                // a new profile starts the form afresh, so no probe result outlives its profile
                key={connectionChoice}
                values={values}
                context={formContext}
                onChange={onSourceChange}
              />
            </SourceIssueContext>
          </Box>
        )}
        {(!showForm || !framed) && (
          <SetupSection id="library-create-section-schedule" title="Zeitplan">
            {scheduleSection}
          </SetupSection>
        )}
      </SetupContext>
    </Box>
  )
}
