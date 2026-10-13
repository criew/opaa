import type { ReactNode } from 'react'
import { useCallback, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { notify } from '../../stores/notificationStore'
import { chatActionTexts, matchChatActions } from './chatActions'
import type {
  ChatActionFormHandlers,
  ChatActionMark,
  ChatActionOption,
  ChatActionSource,
} from './chatActions'
import { findActiveSlashCommand } from './promptCommand'
import type { ActiveSlashCommand } from './promptCommand'

/** One menu entry with the source it came from. */
export interface SourcedChatAction extends ChatActionOption {
  source: ChatActionSource
  /** Unique across every source. */
  key: string
}

/** The mark of the input's text, with the source that set it. */
export interface SelectedChatAction {
  source: ChatActionSource
  mark: ChatActionMark
}

/** Where the chosen action's text goes: the '/'-fragment it replaces. */
interface InsertionRange {
  start: number
  end: number
}

interface UseChatActionsOptions {
  /** The active sources; without any, '/' is plain text. */
  sources: readonly ChatActionSource[]
  spaceId: string | null
  userName: string
  /** Replaces `range` in the input's value with `text` and puts the cursor after it. */
  insertText: (range: InsertionRange, text: string) => void
}

/**
 * The state behind the chat input's '/' menu, over every active action source: which fragment is
 * being typed, the entries it matches, the highlighted one, the form waiting for its values, and
 * the mark of the input's text. The entries are loaded on every opening, so one added or withdrawn
 * meanwhile is current; the previous list stays visible while it loads.
 */
export function useChatActions({ sources, spaceId, userName, insertText }: UseChatActionsOptions) {
  const [command, setCommand] = useState<ActiveSlashCommand | null>(null)
  const [dismissedStart, setDismissedStart] = useState<number | null>(null)
  const [highlight, setHighlight] = useState(0)
  const [options, setOptions] = useState<SourcedChatAction[]>([])
  const [isLoading, setIsLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [pendingForm, setPendingForm] = useState<{
    render: (handlers: ChatActionFormHandlers) => ReactNode
    range: InsertionRange
    selection: SelectedChatAction | null
  } | null>(null)
  const [selected, setSelected] = useState<SelectedChatAction | null>(null)
  const [isInserting, setIsInserting] = useState(false)
  const loadToken = useRef(0)
  // Bumped by every change of the input: an action fetched for an outdated fragment is not inserted.
  const insertionToken = useRef(0)
  const texts = chatActionTexts(sources)
  const enabled = sources.length > 0

  // Actions belong to their space: another space starts without chip, entries or pending form,
  // and nothing still loading for the previous space may land afterwards.
  const [actionSpaceId, setActionSpaceId] = useState(spaceId)
  if (actionSpaceId !== spaceId) {
    setActionSpaceId(spaceId)
    setCommand(null)
    setOptions([])
    setIsLoading(false)
    setPendingForm(null)
    setSelected(null)
  }
  // A layout effect runs in the same task as the commit of that reset, so no response for the
  // previous space can resolve in between.
  useLayoutEffect(() => {
    loadToken.current++
    insertionToken.current++
  }, [spaceId])

  const isOpen = command !== null

  const load = useCallback(() => {
    const token = ++loadToken.current
    setError(null)
    setIsLoading(true)
    const context = { spaceId, userName }
    void Promise.allSettled(
      sources.map(async (source) =>
        (await source.load(context)).map((option): SourcedChatAction => ({
          ...option,
          source,
          key: `${source.id}:${option.id}`,
        })),
      ),
    ).then((results) => {
      if (token !== loadToken.current) return
      const loaded = results.flatMap((result) =>
        result.status === 'fulfilled' ? result.value : [],
      )
      const failure = results.find((result) => result.status === 'rejected')
      // A list from the sources that answered is better than none; only a total failure says so.
      if (failure && loaded.length === 0) {
        const reason = (failure as PromiseRejectedResult).reason
        setError(reason instanceof Error ? reason.message : texts.loadFailed)
      } else {
        setOptions(loaded)
      }
      setIsLoading(false)
    })
  }, [sources, spaceId, userName, texts.loadFailed])

  const matches = useMemo(
    () => (command === null ? [] : matchChatActions(options, command.query)),
    [command, options],
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

  /** Follows the input's value: opens, narrows or closes the menu. */
  const track = useCallback(
    (text: string, cursor: number) => {
      insertionToken.current++
      const detected = enabled ? findActiveSlashCommand(text, cursor) : null
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
    [command, dismissedStart, enabled, load],
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
    async (entry: SourcedChatAction, cursor: number) => {
      if (command === null) return
      const range = { start: command.start, end: cursor }
      const token = ++insertionToken.current
      close()
      setDismissedStart(null)
      setIsInserting(true)
      const { source } = entry
      let result
      try {
        result = await source.choose(entry, { spaceId, userName })
      } catch (err) {
        if (token === insertionToken.current) {
          notify(err instanceof Error ? err.message : source.texts.chooseFailed, 'error')
        }
        return
      } finally {
        setIsInserting(false)
      }
      if (token !== insertionToken.current) return
      const selection = result.mark ? { source, mark: result.mark } : null
      if (result.kind === 'form') {
        setPendingForm({ render: result.renderForm, range, selection })
        return
      }
      insertText(range, result.text)
      setSelected(selection)
    },
    [close, command, insertText, spaceId, userName],
  )

  const completeForm = useCallback(
    (text: string) => {
      if (pendingForm === null) return
      insertionToken.current++
      insertText(pendingForm.range, text)
      setSelected(pendingForm.selection)
      setPendingForm(null)
    },
    [insertText, pendingForm],
  )

  const cancelForm = useCallback(() => setPendingForm(null), [])

  return {
    /** Whether any source is active - without one, '/' is plain text and the hint omits it. */
    enabled,
    texts,
    isOpen,
    matches,
    highlightedIndex,
    setHighlightedIndex: setHighlight,
    moveHighlight,
    isLoading,
    /** Whether the sources offer anything at all - an empty menu says why it is empty. */
    hasOptions: options.length > 0,
    /** While the chosen action is fetched, sending waits - the input is about to change. */
    isInserting,
    error,
    track,
    close,
    dismiss,
    choose,
    /** The form a chosen action waits on, rendered by its source with these handlers. */
    pendingForm: pendingForm?.render ?? null,
    completeForm,
    cancelForm,
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
