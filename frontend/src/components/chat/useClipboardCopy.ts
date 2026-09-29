import { useCallback, useEffect, useRef, useState } from 'react'
import { notify } from '../../stores/notificationStore'
import { announceCopy } from './copyAnnouncer'

/** How long a copy button shows its confirmation. */
const CONFIRMATION_MS = 2000

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
    async (text: string) => {
      try {
        if (!navigator.clipboard) throw new Error('Clipboard API unavailable')
        await navigator.clipboard.writeText(text)
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
