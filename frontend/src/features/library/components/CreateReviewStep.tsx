import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import type { SummaryRow, SourceSummary } from './sources/types'
import SummaryBlock from './setup/SummaryBlock'
import { SETUP_SECTION_TITLES } from './setup/setupContext'

interface CreateReviewStepProps {
  sourceRows: SummaryRow[]
  summary: SourceSummary | null
  scheduleRows: SummaryRow[] | null
  nameRows: SummaryRow[]
  sharingRows: SummaryRow[] | null
  /** Jumps to the step that holds a section and comes back here after it. */
  onChange: (section: 'type' | 'source' | 'name' | 'sharing') => void
}

/** The step „Prüfen“: every entry by section of the setup frame, each with „Ändern“. */
export default function CreateReviewStep({
  sourceRows,
  summary,
  scheduleRows,
  nameRows,
  sharingRows,
  onChange,
}: CreateReviewStepProps) {
  return (
    <Box sx={{ maxWidth: 640 }}>
      <Typography sx={{ fontSize: 13.5, color: 'text.secondary', mb: 1 }}>
        Angelegt wird erst mit „Bibliothek anlegen“.
      </Typography>
      <SummaryBlock title="Quellart" rows={sourceRows} onChange={() => onChange('type')} />
      {summary &&
        (['connection', 'credentials', 'scope'] as const).map((key) =>
          summary[key] ? (
            <SummaryBlock
              key={key}
              title={SETUP_SECTION_TITLES[key]}
              rows={summary[key]!}
              onChange={() => onChange('source')}
            />
          ) : null,
        )}
      {scheduleRows && (
        <SummaryBlock
          title={SETUP_SECTION_TITLES.schedule}
          rows={scheduleRows}
          onChange={() => onChange('source')}
        />
      )}
      <SummaryBlock
        title="Name und Beschreibung"
        rows={nameRows}
        onChange={() => onChange('name')}
      />
      {sharingRows && (
        <SummaryBlock title="Freigaben" rows={sharingRows} onChange={() => onChange('sharing')} />
      )}
    </Box>
  )
}
