import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
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
      <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
        <Chip
          label={locked ? 'Diagnose gesperrt' : 'Diagnose freigegeben'}
          size="small"
          color={locked ? 'default' : 'warning'}
          variant="outlined"
        />
        {canToggle && (
          <Button
            size="small"
            variant="outlined"
            onClick={onToggle}
            disabled={saving}
            aria-label={locked ? 'Diagnosesperre lösen' : 'Diagnosesperre setzen'}
          >
            {saving ? 'Wird gespeichert …' : locked ? 'Sperre lösen' : 'Sperre setzen'}
          </Button>
        )}
      </Stack>
      <Typography variant="caption" component="p" sx={{ color: 'text.secondary', mt: 0.5 }}>
        Solange gesperrt, bleibt diese Bibliothek von einer Suchdiagnose im Rechtekontext einer
        anderen Person ausgeschlossen — dort ist dann weder ein Treffer noch ein Titel aus ihr zu
        sehen.
        {!canToggle &&
          ' Setzen und lösen kann die Sperre nur die für die Bibliothek zuständige Stelle (Eigentümer), nicht die Systemverwaltung als solche.'}
      </Typography>
      {error && (
        <Alert severity="error" sx={{ mt: 1 }} onClose={onDismissError}>
          {error}
        </Alert>
      )}
    </Box>
  )
}
