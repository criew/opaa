import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import DetailSection, { TextAction } from '@/shared/ui/DetailSection'

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
    <DetailSection
      title="Diagnosesperre"
      action={
        canToggle ? (
          <TextAction onClick={onToggle} disabled={saving}>
            {locked ? 'Sperre lösen' : 'Sperre setzen'}
          </TextAction>
        ) : undefined
      }
    >
      <Box sx={{ borderTop: 1, borderColor: 'divider', pt: 1 }}>
        <Typography sx={{ fontSize: 13.5, fontWeight: 600 }}>
          {locked ? 'gesperrt' : 'freigegeben'}
        </Typography>
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
          {locked
            ? 'erscheint in keiner Suchdiagnose einer anderen Person'
            : 'kann in der Suchdiagnose einer anderen Person erscheinen'}
        </Typography>
      </Box>
      {error && (
        <Alert severity="error" sx={{ mt: 1 }} onClose={onDismissError}>
          {error}
        </Alert>
      )}
    </DetailSection>
  )
}
