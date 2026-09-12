import { useEffect, useRef, useState } from 'react'
import type { MailTemplatePreviewResponse } from '../types/api'
import { previewMailTemplate } from '../services/api'

/** Long enough that typing a sentence is one request, short enough to feel live. */
export const MAIL_PREVIEW_DEBOUNCE_MS = 500

interface MailTemplatePreviewState {
  preview: MailTemplatePreviewResponse | null
  error: string | null
  isLoading: boolean
}

/**
 * Die entprellte Vorschau des Vorlagen-Editors (#1542).
 *
 * Zwei Zusicherungen tragen sie: Eine Eingabefolge löst **eine** Anfrage aus, und eine Antwort,
 * die nach einer neueren eintrifft, wird verworfen — ohne die laufende Nummer würde der langsame
 * Server die Vorschau auf einen Stand zurückdrehen, den niemand mehr im Editor stehen hat.
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
