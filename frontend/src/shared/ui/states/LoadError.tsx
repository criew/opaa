import { useRef } from 'react'
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
  moveFocus = true,
}: {
  message: LoadErrorMessage
  detail?: string | null
  onRetry: () => void
  /** `false` where the focus stays elsewhere on purpose, e.g. in the input that drives a menu. */
  moveFocus?: boolean
}) {
  // A detail that only says the same in other words (the store's fallback) adds nothing.
  const extra = detail && !/nicht geladen werden/.test(detail) ? detail.trim() : null
  const rootRef = useRef<HTMLDivElement>(null)

  function retry() {
    // The notice gives way to the loading content; the focus moves to the area that holds it
    // instead of falling back to the page.
    const area = rootRef.current?.parentElement ?? null
    onRetry()
    if (!moveFocus) return
    if (area && !area.hasAttribute('tabindex')) area.setAttribute('tabindex', '-1')
    area?.focus({ preventScroll: true })
  }

  return (
    <Alert
      ref={rootRef}
      severity="error"
      data-load-error=""
      action={
        <Button color="inherit" size="small" onClick={retry}>
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
