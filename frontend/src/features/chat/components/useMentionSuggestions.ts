import { useMemo, useState } from 'react'
import type { Dispatch, RefObject, SetStateAction } from 'react'
import type { SearchScope } from '../stores/chatSettingsPersistence'
import { SPACE_KNOWLEDGE_LABEL, findActiveMention } from './chatInputText'
import type { ActiveMention, MentionSuggestion, SpaceLibrary } from './chatInputText'

interface MentionSuggestionsOptions {
  value: string
  setValue: Dispatch<SetStateAction<string>>
  inputRef: RefObject<HTMLTextAreaElement | null>
  spaceLibraries: SpaceLibrary[]
  scope: SearchScope
  referencedLibraryIds: string[]
  setScopeAll: () => void
  addReferencedLibrary: (libraryId: string) => void
}

/**
 * The '@' mention of the input: the fragment being typed, its suggestions and their highlight,
 * the Escape dismissal, and the selection that turns a suggestion into a chip of the bar.
 */
export function useMentionSuggestions({
  value,
  setValue,
  inputRef,
  spaceLibraries,
  scope,
  referencedLibraryIds,
  setScopeAll,
  addReferencedLibrary,
}: MentionSuggestionsOptions) {
  const [mention, setMention] = useState<ActiveMention | null>(null)
  // -1 means "nothing explicitly highlighted yet" - Enter only selects once ArrowDown/ArrowUp/
  // hover has set an index, so a bare '@' at the end of the text does not select the first
  // suggestion out from under the user on a plain Enter.
  const [highlightedIndex, setHighlightedIndex] = useState(-1)
  // Start index of a mention dismissed via Escape - suppresses reopening the list while the
  // cursor stays inside that same '@'-fragment, until the fragment is left (space, deletion past
  // '@', or a new '@' elsewhere).
  const [dismissedMentionStart, setDismissedMentionStart] = useState<number | null>(null)

  const suggestions = useMemo((): MentionSuggestion[] => {
    if (mention === null) return []
    const query = mention.query.toLowerCase()
    const suggestions: MentionSuggestion[] = []
    // @Space-Wissen is always offered first, regardless of the current scope - re-selecting
    // it while already active is a harmless no-op, and it is the only way back once removed.
    if (SPACE_KNOWLEDGE_LABEL.toLowerCase().includes(query)) {
      suggestions.push({ kind: 'all' })
    }
    const alreadyReferenced = scope === 'libraries' ? referencedLibraryIds : []
    spaceLibraries
      .filter((library) => !alreadyReferenced.includes(library.id))
      .filter((library) => library.name.toLowerCase().includes(query))
      .slice(0, 8 - suggestions.length)
      .forEach((library) => suggestions.push({ kind: 'library', library }))
    return suggestions
  }, [spaceLibraries, mention, referencedLibraryIds, scope])

  /** Closes the suggestion popup without recording a dismissal (used on selection/send). */
  const closeMention = () => {
    setMention(null)
    setHighlightedIndex(-1)
  }

  const selectSuggestion = (suggestion: MentionSuggestion) => {
    if (mention === null) return
    const cursor = inputRef.current?.selectionStart ?? value.length
    const before = value.slice(0, mention.start)
    const after = value.slice(cursor)
    const nextValue = `${before}${after}`
    setValue(nextValue)
    if (suggestion.kind === 'all') {
      setScopeAll()
    } else {
      addReferencedLibrary(suggestion.library.id)
    }
    setDismissedMentionStart(null)
    closeMention()
    requestAnimationFrame(() => {
      inputRef.current?.focus()
      inputRef.current?.setSelectionRange(before.length, before.length)
    })
  }

  /** Opens, keeps or closes the mention for the text just typed; `locked` keeps it closed. */
  const trackMention = (nextValue: string, cursor: number, locked: boolean) => {
    // The '@' selection changes the search scope, so it stays closed while the scope is locked.
    const detected = locked ? null : findActiveMention(nextValue, cursor)
    if (detected === null) {
      // Left the fragment entirely (space, deleted past '@', ...) - any earlier dismissal no
      // longer applies.
      setMention(null)
      setDismissedMentionStart(null)
    } else if (detected.start === dismissedMentionStart) {
      // Still inside the '@'-fragment the user dismissed via Escape - keep it closed instead of
      // reopening on the very next keystroke.
      setMention(null)
    } else {
      setMention(detected)
      setHighlightedIndex(-1)
      setDismissedMentionStart(null)
    }
  }

  /** Escape: closes the list and keeps it closed while the cursor stays in this fragment. */
  const dismissMention = () => {
    if (mention === null) return
    setDismissedMentionStart(mention.start)
    closeMention()
  }

  /** After sending: closes the list and forgets any earlier dismissal. */
  const resetMention = () => {
    setDismissedMentionStart(null)
    closeMention()
  }

  return {
    mention,
    highlightedIndex,
    setHighlightedIndex,
    suggestions,
    closeMention,
    selectSuggestion,
    trackMention,
    dismissMention,
    resetMention,
  }
}
