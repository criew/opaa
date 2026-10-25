import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'
import { confirmAction } from '@/shared/stores/confirmStore'
import TabSection from '@/shared/ui/TabSection'

interface DiagnosticsLockControlProps {
  locked: boolean
  canToggle: boolean
  saving: boolean
  error: string | null
  onToggle: () => void
  onDismissError: () => void
}

// Renders the Diagnosesperre state (docs/features/hybrid-retrieval.md, Leitplanke (e)) as one
// line, with the toggle at the section's edge for the responsible body only. What the lock means
// is said once, in the question before it changes. The 403 a caller without that standing gets
// back from PUT .../diagnostics-lock is shown verbatim (see normalizeError).
export default function DiagnosticsLockControl({
  locked,
  canToggle,
  saving,
  error,
  onToggle,
  onDismissError,
}: DiagnosticsLockControlProps) {
  async function ask() {
    const confirmed = await confirmAction(
      locked
        ? {
            question: 'Diagnosesperre lösen?',
            consequence:
              'Die Bibliothek kann dann in Suchdiagnosen für andere Personen erscheinen.',
            confirmLabel: 'Sperre lösen',
            tone: 'caution',
          }
        : {
            question: 'Diagnosesperre setzen?',
            consequence:
              'Gesperrt erscheint die Bibliothek in keiner Suchdiagnose für andere Personen.',
            confirmLabel: 'Sperre setzen',
            tone: 'neutral',
          },
    )
    if (confirmed) onToggle()
  }

  return (
    <TabSection
      title="Diagnosesperre"
      action={
        canToggle ? (
          <Button
            size="small"
            onClick={() => void ask()}
            disabled={saving}
            aria-label={locked ? 'Diagnosesperre lösen' : 'Diagnosesperre setzen'}
          >
            {saving ? 'Wird gespeichert …' : locked ? 'Sperre lösen' : 'Sperre setzen'}
          </Button>
        ) : undefined
      }
    >
      <Typography sx={{ fontSize: 13.5 }}>{locked ? 'Gesperrt' : 'Freigegeben'}</Typography>
      {error && (
        <Alert severity="error" sx={{ mt: 1 }} onClose={onDismissError}>
          {error}
        </Alert>
      )}
    </TabSection>
  )
}
