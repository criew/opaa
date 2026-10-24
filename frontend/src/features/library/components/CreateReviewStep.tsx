import type { ReactNode } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'
import KeyValueList from '@/shared/ui/KeyValueList'
import type { SourceSummary } from './sources/types'
import { SummaryConnection, SummarySelection } from './setup/SourceSummaryView'
import { scheduleFrequencyLabel, scheduleWeekdayLabel } from './scheduleLabels'
import type { LibraryScheduleValues } from '../utils/librarySchedule'

interface ReviewSection {
  title: string
  /** The step „Ändern" returns to; absent for a section that cannot be changed here. */
  step?: number
  content: ReactNode
}

interface CreateReviewStepProps {
  sourceTypeName: string
  connectionName: string | null
  summary: SourceSummary | null
  schedule: LibraryScheduleValues | null
  startFirstRun: boolean
  name: string
  description: string
  owner: string
  grantCount: number | null
  steps: { sourceType: number; setup: number | null; name: number; sharing: number | null }
  onChange: (step: number, focusField?: string) => void
}

/**
 * The step „Prüfen" before „Bibliothek anlegen": every answer of the wizard by section, each with
 * „Ändern" that leads back to its step; from there „Weiter" returns here.
 */
export default function CreateReviewStep({
  sourceTypeName,
  connectionName,
  summary,
  schedule,
  startFirstRun,
  name,
  description,
  owner,
  grantCount,
  steps,
  onChange,
}: CreateReviewStepProps) {
  const sections: ReviewSection[] = [
    {
      title: 'Quellart',
      step: steps.sourceType,
      content: <Typography sx={{ fontSize: 13.5 }}>{sourceTypeName}</Typography>,
    },
  ]
  if (summary && steps.setup !== null) {
    sections.push({
      title: 'Verbindung',
      step: steps.setup,
      content: (
        <Box>
          {connectionName && !summary.connection.some((row) => row.label === 'Zugang') && (
            <KeyValueList entries={[{ label: 'Zugang', value: connectionName }]} sx={{ mb: 0.5 }} />
          )}
          <SummaryConnection summary={summary} />
        </Box>
      ),
    })
    if (summary.selection) {
      sections.push({
        title: 'Auswahl',
        step: steps.setup,
        content: <SummarySelection summary={summary} limit={5} />,
      })
    }
  }
  if (schedule && steps.setup !== null) {
    sections.push({
      title: 'Zeitplan',
      step: steps.setup,
      content: (
        <KeyValueList
          entries={[
            {
              label: 'Rhythmus',
              value:
                schedule.frequency === 'DISABLED'
                  ? 'Aus'
                  : [
                      scheduleFrequencyLabel(schedule.frequency),
                      schedule.frequency === 'WEEKLY' ? scheduleWeekdayLabel(schedule.weekday) : '',
                      schedule.frequency !== 'HOURLY' ? `${schedule.time} Uhr` : '',
                    ]
                      .filter(Boolean)
                      .join(', '),
            },
            { label: 'Erster Lauf', value: startFirstRun ? 'Sofort nach dem Anlegen' : 'Von Hand' },
          ]}
        />
      ),
    })
  }
  sections.push({
    title: 'Name',
    step: steps.name,
    content: (
      <KeyValueList
        entries={[
          { label: 'Name', value: name.trim() },
          { label: 'Beschreibung', value: description.trim(), hidden: description.trim() === '' },
        ]}
      />
    ),
  })
  if (steps.sharing !== null) {
    sections.push({
      title: 'Freigaben',
      step: steps.sharing,
      content: (
        <KeyValueList
          entries={[
            { label: 'Eigentümer', value: owner },
            {
              label: 'Vorgemerkt',
              value:
                grantCount === 0
                  ? 'keine Freigaben'
                  : grantCount === 1
                    ? '1 Freigabe'
                    : `${grantCount} Freigaben`,
            },
          ]}
        />
      ),
    })
  }

  return (
    <Box sx={{ maxWidth: 720 }}>
      {sections.map((section) => (
        <Box
          key={section.title}
          component="section"
          aria-label={section.title}
          sx={{
            display: 'grid',
            gridTemplateColumns: { xs: '1fr auto', sm: '10rem 1fr auto' },
            columnGap: 2,
            rowGap: 0.5,
            py: 2,
            borderBottom: 1,
            borderColor: 'divider',
          }}
        >
          <Typography component="h3" sx={{ fontSize: 14, fontWeight: 600 }}>
            {section.title}
          </Typography>
          <Box
            sx={{
              gridColumn: { xs: '1 / -1', sm: 'auto' },
              gridRow: { xs: 2, sm: 'auto' },
              minWidth: 0,
            }}
          >
            {section.content}
          </Box>
          {section.step !== undefined && (
            <Box sx={{ gridColumn: { xs: 2, sm: 3 }, gridRow: 1, justifySelf: 'end' }}>
              <Button
                size="small"
                onClick={() => onChange(section.step as number)}
                aria-label={`${section.title} ändern`}
              >
                Ändern
              </Button>
            </Box>
          )}
        </Box>
      ))}
    </Box>
  )
}
