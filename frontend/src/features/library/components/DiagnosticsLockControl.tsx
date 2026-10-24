import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'

interface DiagnosticsLockControlProps {
  locked: boolean
  canToggle: boolean
  saving: boolean
  error: string | null
  onToggle: () => void
  onDismissError: () => void
}

// Renders the Diagnosesperre state (docs/features/hybrid-retrieval.md, Leitplanke (e)) -
// visible to everyone who may read the library (see LibraryResponse#diagnosticsLocked), but only
// togglable by the responsible body itself. The 403 a caller without that standing gets back from
// PUT .../diagnostics-lock is shown verbatim (see normalizeError) - it already names, in German,
// who may act instead.
export default function DiagnosticsLockControl({
  locked,
  canToggle,
  saving,
  error,
  onToggle,
  onDismissError,
}: DiagnosticsLockControlProps) {
  return (
    <Box>
      <Stack
        direction="row"
        spacing={1.5}
        sx={{ alignItems: 'baseline', justifyContent: 'space-between', flexWrap: 'wrap' }}
      >
        <Box sx={{ minWidth: 0 }}>
          <Typography sx={{ fontSize: 13.5, fontWeight: 500 }}>
            {locked ? 'Diagnose gesperrt' : 'Diagnose freigegeben'}
          </Typography>
          <Typography variant="caption" component="p" sx={{ color: 'text.secondary' }}>
            Gesperrt erscheint die Bibliothek in keiner Suchdiagnose für andere Personen.
          </Typography>
        </Box>
        {canToggle && (
          <Button
            size="small"
            onClick={onToggle}
            disabled={saving}
            aria-label={locked ? 'Diagnosesperre lösen' : 'Diagnosesperre setzen'}
          >
            {saving ? 'Wird gespeichert …' : locked ? 'Sperre lösen' : 'Sperre setzen'}
          </Button>
        )}
      </Stack>
      {error && (
        <Alert severity="error" sx={{ mt: 1 }} onClose={onDismissError}>
          {error}
        </Alert>
      )}
    </Box>
  )
}
