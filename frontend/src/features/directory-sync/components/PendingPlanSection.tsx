import { useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import FormField from '@/shared/ui/form/FormField'
import Typography from '@mui/material/Typography'
import type { DirectorySyncPendingPlanResponse } from '@/shared/api/types'
import { ageSinceLabel } from '@/features/principal'
import DirectorySyncReportView from './DirectorySyncReportView'
import { formatDateTime } from './directorySyncLabels'
import { fontSize } from '@/shared/theme/tokens'

interface PendingPlanSectionProps {
  plan: DirectorySyncPendingPlanResponse
  busy: boolean
  /** Why confirming is refused right now; discarding stays possible. */
  confirmBlocker: string | null
  /** Both resolve true when the decision went through, so the reason can be cleared. */
  onConfirm: (reason: string) => Promise<boolean>
  onDiscard: (reason: string) => Promise<boolean>
}

/**
 * The plan a run left for a decision (ADR-0036, Entscheidung 3): what it would do, and the two
 * answers - each only with a reason, which goes into the audit log.
 */
export default function PendingPlanSection({
  plan,
  busy,
  confirmBlocker,
  onConfirm,
  onDiscard,
}: PendingPlanSectionProps) {
  const [reason, setReason] = useState('')
  const trimmed = reason.trim()

  async function decide(action: (reason: string) => Promise<boolean>) {
    if (await action(trimmed)) setReason('')
  }

  return (
    <Box>
      <Typography sx={{ fontSize: fontSize.text, fontWeight: 600 }}>
        Wartet seit {ageSinceLabel(plan.createdAt)} auf Ihre Entscheidung
      </Typography>
      <Typography sx={{ fontSize: fontSize.text, color: 'text.secondary', mt: 0.5 }}>
        Erstmals vorgelegt am {formatDateTime(plan.createdAt)}. Der Lauf hätte mehr entzogen oder
        gesperrt, als OPAA ohne Rückfrage anwendet; bis zu Ihrer Entscheidung gilt der bisherige
        Stand. Beim Bestätigen liest OPAA das Verzeichnis neu — weicht es ab, wird der Plan neu
        vorgelegt statt angewendet.
      </Typography>

      <Typography component="h4" sx={{ fontSize: fontSize.text, fontWeight: 600, mt: 2 }}>
        Was der Plan ändern würde
      </Typography>
      <DirectorySyncReportView report={plan.report} showOutcome={false} />

      <FormField
        size="small"
        fullWidth
        required
        label="Grund der Entscheidung"
        helperText="Pflichtangabe, geht ins Protokoll."
        value={reason}
        onChange={(event) => setReason(event.target.value)}
        sx={{ mt: 2 }}
      />
      <Stack direction="row" spacing={1} sx={{ mt: 1.5 }}>
        <Button
          variant="contained"
          size="small"
          disabled={busy || trimmed === '' || Boolean(confirmBlocker)}
          onClick={() => void decide(onConfirm)}
        >
          Plan bestätigen
        </Button>
        <Button
          size="small"
          color="error"
          disabled={busy || trimmed === ''}
          onClick={() => void decide(onDiscard)}
        >
          Plan verwerfen
        </Button>
      </Stack>
      {confirmBlocker && (
        <Typography sx={{ fontSize: fontSize.meta, color: 'text.secondary', mt: 1 }}>
          {confirmBlocker}
        </Typography>
      )}
    </Box>
  )
}
