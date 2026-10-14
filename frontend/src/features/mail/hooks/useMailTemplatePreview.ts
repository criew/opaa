import { useEffect, useRef, useState } from 'react'
import type { MailTemplatePreviewResponse } from '@/shared/api/types'
import { previewMailTemplate } from '../api/mailApi'

/** Long enough that typing a sentence is one request, short enough to feel live. */
export const MAIL_PREVIEW_DEBOUNCE_MS = 500

interface MailTemplatePreviewState {
  preview: MailTemplatePreviewResponse | null
  error: string | null
  isLoading: boolean
}

/**
 * The debounced preview of the template editor.
 *
 * Two guarantees carry it: a sequence of inputs triggers **one** request, and a response
 * that arrives after a newer one is discarded — without the sequence number the slow
 * server would turn the preview back to a state nobody has in the editor any more.
 */
export function useMailTemplatePreview(
  templateKey: string,
  subject: string,
  bodyPlain: string,
): MailTemplatePreviewState {
  const [state, setState] = useState<MailTemplatePreviewState>({
    preview: null,
    error: null,
    isLoading: false,
  })
  const latestRequest = useRef(0)

  useEffect(() => {
    const timer = setTimeout(() => {
      const requestId = latestRequest.current + 1
      latestRequest.current = requestId
      setState((current) => ({ ...current, isLoading: true }))
      previewMailTemplate(templateKey, { subject, bodyPlain })
        .then((preview) => {
          if (requestId !== latestRequest.current) return
          setState({ preview, error: null, isLoading: false })
        })
        .catch((err: unknown) => {
          if (requestId !== latestRequest.current) return
          setState({
            preview: null,
            error: err instanceof Error ? err.message : 'Die Vorschau ist fehlgeschlagen.',
            isLoading: false,
          })
        })
    }, MAIL_PREVIEW_DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [templateKey, subject, bodyPlain])

  return state
}
