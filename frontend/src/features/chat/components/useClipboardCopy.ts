import { useCallback, useEffect, useRef, useState } from 'react'
import { notify } from '@/shared/stores/notificationStore'
import { announceCopy } from './copyAnnouncer'

/** How long a copy button shows its confirmation. */
const CONFIRMATION_MS = 2000

/**
 * Writes `text` and, where the browser supports `ClipboardItem`, `html` as a second flavour; a
 * browser without it - or one that refuses the multi-flavour write - gets the plain text alone.
 */
async function writeClipboard(text: string, html?: string): Promise<void> {
  if (html !== undefined && typeof ClipboardItem !== 'undefined' && navigator.clipboard.write) {
    try {
      await navigator.clipboard.write([
        new ClipboardItem({
          'text/plain': new Blob([text], { type: 'text/plain' }),
          'text/html': new Blob([html], { type: 'text/html' }),
        }),
      ])
      return
    } catch {
      // fall through to the plain-text write
    }
  }
  await navigator.clipboard.writeText(text)
}

/**
 * Copying from the chat history, confirmed at the button itself: frequent, small copies would
 * flood the popup notifications (guidelines 5.9). A failure still goes there, with the way out by
 * hand - a person who believes they copied an answer and did not finds out only when pasting.
 */
export function useClipboardCopy(what: string) {
  const [copied, setCopied] = useState(false)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(
    () => () => {
      if (timer.current) clearTimeout(timer.current)
    },
    [],
  )

  const copy = useCallback(
    async (text: string, html?: string) => {
      try {
        if (!navigator.clipboard) throw new Error('Clipboard API unavailable')
        await writeClipboard(text, html)
        setCopied(true)
        announceCopy(`${what} wurde kopiert.`)
        if (timer.current) clearTimeout(timer.current)
        timer.current = setTimeout(() => setCopied(false), CONFIRMATION_MS)
      } catch {
        notify(`${what} konnte nicht kopiert werden – bitte manuell markieren.`, 'error')
      }
    },
    [what],
  )

  return { copied, copy }
}
