import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'

/** „<Objekte> konnten nicht geladen werden." or, for one object, „… konnte nicht geladen werden." */
export type LoadErrorMessage =
  `${string} konnten nicht geladen werden.` | `${string} konnte nicht geladen werden.`

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
  // A detail that only says the same in other words (the store's fallback) adds nothing.
  const extra = detail && !/nicht geladen werden/.test(detail) ? detail.trim() : null
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
      {/* Separate spans, so the sentence and the cause can each be found on their own. */}
      <span>{message}</span>
      {extra && <span>{` ${extra}`}</span>}
    </Alert>
  )
}
