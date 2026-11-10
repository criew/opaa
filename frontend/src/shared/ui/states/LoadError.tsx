import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'

/** „<Objekte> konnten nicht geladen werden." or, for one object, „… konnte nicht geladen werden." */
export type LoadErrorMessage =
  | `${string} konnten nicht geladen werden.`
  | `${string} konnte nicht geladen werden.`

/**
 * The one load error of the application (guidelines 5.7): a notice with „Erneut laden" in place of
 * the content that failed to load - never red running text and never next to an empty text, so
 * „failed" and „empty" cannot be mistaken for each other. `detail` adds the cause the server
 * named, where it tells the reader more than the sentence.
 */
export default function LoadError({
  message,
  detail,
  onRetry,
}: {
  message: LoadErrorMessage
  detail?: string | null
  onRetry: () => void
}) {
  const extra = detail && detail.trim() !== message ? ` ${detail.trim()}` : ''
  return (
    <Alert
      severity="error"
      data-load-error=""
      action={
        <Button color="inherit" size="small" onClick={onRetry}>
          Erneut laden
        </Button>
      }
    >
      {message}
      {extra}
    </Alert>
  )
}
