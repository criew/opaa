import Alert from '@mui/material/Alert'
import { TextAction } from '@/shared/ui/DetailSection'
import ValueRow from '@/shared/ui/ValueRow'

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
    <ValueRow
      title="Suchdiagnose"
      value={locked ? 'Gesperrt für Suchdiagnosen anderer' : 'Nicht gesperrt'}
      action={
        canToggle ? (
          <TextAction onClick={onToggle} disabled={saving} context="Suchdiagnose">
            {locked ? 'Entsperren' : 'Sperren'}
          </TextAction>
        ) : undefined
      }
    >
      {error && (
        <Alert severity="error" onClose={onDismissError}>
          {error}
        </Alert>
      )}
    </ValueRow>
  )
}
