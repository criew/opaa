import { useRef } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
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
  dense = false,
}: {
  message: LoadErrorMessage
  detail?: string | null
  onRetry: () => void
  /** `false` where the focus stays elsewhere on purpose, e.g. in the input that drives a menu. */
  moveFocus?: boolean
  /**
   * For a narrow column such as the sidebar: the sentence alone, without icon and server cause,
   * and „Erneut laden" below it, so the notice keeps to three short lines.
   */
  dense?: boolean
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

  const retryButton = (
    <Button color="inherit" size="small" onClick={retry}>
      Erneut laden
    </Button>
  )

  if (dense) {
    return (
      <Alert
        ref={rootRef}
        severity="error"
        icon={false}
        data-load-error=""
        sx={{ px: 1.5, '& .MuiAlert-message': { py: 0.75 } }}
      >
        <span>{message}</span>
        <Box sx={{ mt: 0.5, ml: -1 }}>{retryButton}</Box>
      </Alert>
    )
  }

  return (
    <Alert ref={rootRef} severity="error" data-load-error="" action={retryButton}>
      {/* Separate spans, so the sentence and the cause can each be found on their own. */}
      <span>{message}</span>
      {extra && <span>{` ${extra}`}</span>}
    </Alert>
  )
}
