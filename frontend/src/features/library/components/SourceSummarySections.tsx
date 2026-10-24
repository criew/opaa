import type { ReactNode } from 'react'
import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'
import PageSection from '@/shared/ui/PageSection'
import type { ConnectionProfileRef } from '@/shared/api/types'
import { scheduleValuesFrom } from '../utils/librarySchedule'
import { scheduleSummary } from './setup/scheduleSummary'
import { SETUP_SECTION_TITLES } from './setup/setupContext'
import { SummaryRows } from './setup/SummaryBlock'
import { sourceRegistration } from './sources/registry'
import { sourceConnectionOfRef } from './sources/sourceConnection'
import type { SourceFormContext, StoredLibrarySource } from './sources/types'

interface SourceSummarySectionsProps {
  library: StoredLibrarySource & {
    connectionProfile?: ConnectionProfileRef | null
    privateLibrary?: boolean
    fullSyncIntervalDefaultDays?: number | null
  }
  libraryId: string
  /** Managers see every section and „Quelle bearbeiten“; readers see Umfang only. */
  manager: boolean
  onEdit: () => void
  children: { connectionExtras?: ReactNode; scheduleExtras?: ReactNode }
}

/**
 * The Reiter „Quelle“ in the order of the setup frame - Verbindung, Zugangsdaten, Umfang,
 * Zeitplan - with the rows the step „Prüfen“ shows, read from the stored configuration. One
 * „Quelle bearbeiten“ opens the same frame in a dialog.
 */
export default function SourceSummarySections({
  library,
  libraryId,
  manager,
  onEdit,
  children,
}: SourceSummarySectionsProps) {
  const configuration = sourceRegistration(library.sourceType)?.configuration
  if (!configuration?.summary) return null
  const connection =
    (library.connectionProfile && sourceConnectionOfRef(library.connectionProfile)) ?? undefined
  const context: SourceFormContext = {
    mode: 'edit',
    sourceType: library.sourceType,
    idPrefix: 'source-view',
    libraryId,
    credentialsStored: Boolean(library.sourceCredentialsSet),
    originalSourceUrl: library.sourceUrl,
    connection,
    privateLibrary: library.privateLibrary,
  }
  const summary = configuration.summary(configuration.fromLibrary(library), context)
  const credentials = summary.credentials?.map((row) => ({
    ...row,
    value: library.sourceCredentialsSet ? row.value : 'nicht gesetzt',
  }))
  const rhythm = configuration.fullSyncRhythm
    ? {
        intervalDays:
          (library.sourceSettings?.fullSyncIntervalDays as number | null | undefined) ?? null,
        defaultDays: library.fullSyncIntervalDefaultDays ?? null,
      }
    : undefined
  const edit = (
    <Button size="small" variant="outlined" onClick={onEdit} sx={{ flexShrink: 0 }}>
      Quelle bearbeiten
    </Button>
  )
  if (!manager) {
    return (
      <>
        {summary.scope && (
          <PageSection title={SETUP_SECTION_TITLES.scope}>
            <SummaryRows rows={summary.scope} />
          </PageSection>
        )}
        <Typography sx={{ fontSize: 13, color: 'text.secondary', mt: 2 }}>
          Verbindung, Zeitplan und Läufe sehen nur Verwaltende.
        </Typography>
      </>
    )
  }
  return (
    <>
      <PageSection title={SETUP_SECTION_TITLES.connection} action={edit}>
        {/* the Zugang line of the panel below names the profile; no second row for it */}
        {summary.connection && (
          <SummaryRows rows={summary.connection.filter((row) => row.label !== 'Zugang')} />
        )}
        {children.connectionExtras}
      </PageSection>
      {credentials && (
        <PageSection title={SETUP_SECTION_TITLES.credentials}>
          <SummaryRows rows={credentials} />
        </PageSection>
      )}
      {summary.scope && (
        <PageSection title={SETUP_SECTION_TITLES.scope}>
          <SummaryRows rows={summary.scope} />
        </PageSection>
      )}
      <PageSection title={SETUP_SECTION_TITLES.schedule}>
        <SummaryRows rows={scheduleSummary(scheduleValuesFrom(library.schedule, rhythm), rhythm)} />
        {children.scheduleExtras}
      </PageSection>
    </>
  )
}
