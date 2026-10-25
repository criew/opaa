import Alert from '@mui/material/Alert'
import AlertTitle from '@mui/material/AlertTitle'
import Button from '@mui/material/Button'
import type { RetentionPause } from '@/shared/api/types'

interface RetentionPauseNoticeProps {
  pauses: RetentionPause[]
  /** Sets the values shown as the administration's own, which ends the pause. */
  onConfirm: () => void
}

/**
 * Why the nightly deletion pauses: a retired variable a running instance still sets to a longer
 * period. The variable is never read as a value; it only holds the deletion back until the
 * administration sets a value of its own or the operator removes it.
 */
export default function RetentionPauseNotice({ pauses, onConfirm }: RetentionPauseNoticeProps) {
  if (pauses.length === 0) return null
  return (
    <Alert
      severity="warning"
      sx={{ mb: 2 }}
      action={
        <Button color="inherit" size="small" onClick={onConfirm}>
          Angezeigte Werte festlegen
        </Button>
      }
    >
      <AlertTitle>Löschen pausiert</AlertTitle>
      {pauses.map((pause) => (
        <div key={`${pause.variable}-${pause.retiredValue}`}>
          Die Variable <code>{pause.variable}</code> ist auf einer laufenden Instanz noch auf{' '}
          {pause.retiredValue} gesetzt und wird nicht gelesen; hier gilt {pause.effectiveValue}.
        </div>
      ))}
      Gelöscht wird erst wieder, wenn hier ein Wert gespeichert ist, auch die angezeigten, oder die
      Variable entfernt ist.
    </Alert>
  )
}
