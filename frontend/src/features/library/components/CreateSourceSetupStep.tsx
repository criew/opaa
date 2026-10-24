import type { Ref } from 'react'
import Box from '@mui/material/Box'
import FormControlLabel from '@mui/material/FormControlLabel'
import Switch from '@mui/material/Switch'
import Typography from '@mui/material/Typography'
import type { ConnectionProfileOptionsState } from '@/features/connection'
import type {
  ConnectionProfileOption,
  PendingSourceConnection,
  SourceTypeDescriptor,
} from '@/shared/api/types'
import type { SourceIssue } from '../utils/sourceIssue'
import type { ConfluenceFullSyncRhythm, LibraryScheduleValues } from '../utils/librarySchedule'
import ConnectionProfileSelect from './ConnectionProfileSelect'
import LibraryScheduleForm from './LibraryScheduleForm'
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
  issues: SourceIssue[]
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

function clockTime(value: string): string {
  return new Date(value).toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' })
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
  const scheduleIssue = issues.find((issue) => issue.field === 'schedule')?.message
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
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
      {consentProfile && pendingConnection && (
        // Variante C: the consent folds into one line; „Verbindung" below shows the state
        <Typography sx={{ fontSize: 13, color: 'text.secondary', maxWidth: 640 }}>
          Zustimmung
          {pendingConnection.accountLabel ? ` als „${pendingConnection.accountLabel}“` : ''} gilt
          bis {clockTime(pendingConnection.expiresAt)} Uhr; legen Sie die Bibliothek bis dahin an.
        </Typography>
      )}
      {consentProfile && !pendingConnection && (
        <Box sx={{ maxWidth: 640 }}>
          <SourceConsentPanel
            profileName={consentProfile.name}
            pending={pendingConnection}
            confirmed={serviceAccountConfirmed}
            onConfirmedChange={onServiceAccountConfirmedChange}
            confirmationMissing={confirmationMissing}
            busy={connectingSource}
            onConnect={onConnectSource}
          />
        </Box>
      )}
      {SourceForm && connectionChoice !== null && !awaitsConsent && (
        <Box ref={sourceFormRef}>
          <SourceIssueContext value={issues}>
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

      {/* Schedule and immediate start apply to every connector type. */}
      <Box sx={{ maxWidth: 640 }} data-source-field="schedule" tabIndex={-1}>
        <Typography component="h3" sx={{ fontSize: 16, fontWeight: 600, mb: 1.5 }}>
          Zeitplan
        </Typography>
        <LibraryScheduleForm
          idPrefix="library-create-schedule"
          values={schedule}
          onChange={onScheduleChange}
          confluence={confluenceRhythm}
        />
        <FormControlLabel
          sx={{ mt: 2 }}
          control={
            <Switch
              checked={startFirstRun}
              onChange={(e) => onStartFirstRunChange(e.target.checked)}
            />
          }
          label="Erste Indizierung sofort nach dem Anlegen starten"
        />
        {scheduleIssue && (
          <Typography sx={{ fontSize: 12.5, color: 'error.main', mt: 1 }}>
            {scheduleIssue}
          </Typography>
        )}
        <Typography sx={{ fontSize: 12, color: 'text.secondary', mt: 0.5 }}>
          {startFirstRun
            ? firstRunHint
            : 'Ohne Sofortstart beginnt die Indizierung erst über „Jetzt indizieren“ auf der Detailseite oder über den Zeitplan.'}
        </Typography>
      </Box>
    </Box>
  )
}
