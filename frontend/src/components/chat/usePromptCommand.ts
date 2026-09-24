import { useCallback, useMemo, useRef, useState } from 'react'
import { getPromptForInsertion, listAvailablePrompts } from '../../services/promptChatApi'
import { notify } from '../../stores/notificationStore'
import type { AvailablePrompt, PromptForInsertion } from '../../types/api'
import { findActiveSlashCommand, matchPrompts, resolvePromptText } from './promptTemplate'
import type { ActiveSlashCommand } from './promptTemplate'

/** The prompt a question in the input was built from - shown as a chip, sent as usedPromptId. */
export interface SelectedPrompt {
  id: string
  title: string
}

/** Where the chosen prompt's text goes: the '/'-fragment it replaces. */
interface InsertionRange {
  start: number
  end: number
}

interface UsePromptCommandOptions {
  spaceId: string | null
  userName: string
  /** Replaces `range` in the input's value with `text` and puts the cursor after it. */
  insertText: (range: InsertionRange, text: string) => void
}

/**
 * The state behind the chat input's '/' command: which fragment is being typed, the
 * prompts it matches, the highlighted one, the prompt waiting for its form, and the prompt the
 * input's text was built from. The selection is loaded on every opening, so a prompt added or
 * withdrawn meanwhile is current; the previous list stays visible while it loads.
 */
export function usePromptCommand({ spaceId, userName, insertText }: UsePromptCommandOptions) {
  const [command, setCommand] = useState<ActiveSlashCommand | null>(null)
  const [dismissedStart, setDismissedStart] = useState<number | null>(null)
  const [highlight, setHighlight] = useState(0)
  const [prompts, setPrompts] = useState<AvailablePrompt[]>([])
  const [isLoading, setIsLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [pendingForm, setPendingForm] = useState<{
    prompt: PromptForInsertion
    range: InsertionRange
  } | null>(null)
  const [selected, setSelected] = useState<SelectedPrompt | null>(null)
  const [isInserting, setIsInserting] = useState(false)
  const loadToken = useRef(0)
  // Bumped by every change of the input: a prompt fetched for an outdated fragment is not inserted.
  const insertionToken = useRef(0)

  const isOpen = command !== null

  const load = useCallback(() => {
    const token = ++loadToken.current
    setIsLoading(true)
    setError(null)
    listAvailablePrompts(spaceId)
      .then((loaded) => {
        if (token !== loadToken.current) return
        setPrompts(loaded)
      })
      .catch((err: unknown) => {
        if (token !== loadToken.current) return
        setError(err instanceof Error ? err.message : 'Prompts konnten nicht geladen werden')
      })
      .finally(() => {
        if (token === loadToken.current) setIsLoading(false)
      })
  }, [spaceId])

  const matches = useMemo(
    () => (command === null ? [] : matchPrompts(prompts, command.query)),
    [command, prompts],
  )
  // A reloaded, shorter list must not leave the highlight behind its last option.
  const highlightedIndex = matches.length === 0 ? -1 : Math.min(highlight, matches.length - 1)

  const moveHighlight = useCallback(
    (delta: 1 | -1) => {
      const count = matches.length
      if (count === 0) return
      setHighlight((current) => (Math.min(current, count - 1) + delta + count) % count)
    },
    [matches.length],
  )

  /** Follows the input's value: opens, narrows or closes the selection. */
  const track = useCallback(
    (text: string, cursor: number) => {
      insertionToken.current++
      const detected = findActiveSlashCommand(text, cursor)
      if (detected === null) {
        setCommand(null)
        setDismissedStart(null)
      } else if (detected.start === dismissedStart) {
        setCommand(null)
      } else {
        if (command === null || command.start !== detected.start) load()
        setCommand(detected)
        setHighlight(0)
      }
      if (text.trim() === '') setSelected(null)
    },
    [command, dismissedStart, load],
  )

  const close = useCallback(() => {
    setCommand(null)
    setHighlight(0)
  }, [])

  /** Escape: closed until the fragment is left, like the '@' selection. */
  const dismiss = useCallback(() => {
    if (command !== null) setDismissedStart(command.start)
    close()
  }, [close, command])

  const choose = useCallback(
    async (entry: AvailablePrompt, cursor: number) => {
      if (command === null) return
      const range = { start: command.start, end: cursor }
      const token = ++insertionToken.current
      close()
      setDismissedStart(null)
      setIsInserting(true)
      let prompt: PromptForInsertion
      try {
        prompt = await getPromptForInsertion(entry.libraryId, entry.id)
      } catch (err) {
        if (token === insertionToken.current) {
          notify(
            err instanceof Error ? err.message : 'Der Prompt konnte nicht geladen werden',
            'error',
          )
        }
        return
      } finally {
        setIsInserting(false)
      }
      if (token !== insertionToken.current) return
      if (prompt.variables.length > 0) {
        setPendingForm({ prompt, range })
        return
      }
      insertText(range, resolvePromptText(prompt.text, [], {}, { userName }))
      setSelected({ id: prompt.id, title: prompt.title })
    },
    [close, command, insertText, userName],
  )

  const completeForm = useCallback(
    (text: string) => {
      if (pendingForm === null) return
      insertionToken.current++
      insertText(pendingForm.range, text)
      setSelected({ id: pendingForm.prompt.id, title: pendingForm.prompt.title })
      setPendingForm(null)
    },
    [insertText, pendingForm],
  )

  return {
    isOpen,
    matches,
    highlightedIndex,
    setHighlightedIndex: setHighlight,
    moveHighlight,
    isLoading,
    /** While the chosen prompt is fetched, sending waits - the input is about to change. */
    isInserting,
    error,
    track,
    close,
    dismiss,
    choose,
    pendingForm: pendingForm?.prompt ?? null,
    cancelForm: () => setPendingForm(null),
    completeForm,
    selected,
    clearSelected: () => setSelected(null),
    /** After sending: nothing still in flight may land in the next question's input. */
    reset: () => {
      insertionToken.current++
      setCommand(null)
      setHighlight(0)
      setSelected(null)
    },
  }
}
