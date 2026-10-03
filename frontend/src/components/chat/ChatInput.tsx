import type { ChangeEvent, KeyboardEvent } from 'react'
import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import Box from '@mui/material/Box'
import Chip from '@mui/material/Chip'
import List from '@mui/material/List'
import ListItemButton from '@mui/material/ListItemButton'
import ClickAwayListener from '@mui/material/ClickAwayListener'
import Paper from '@mui/material/Paper'
import Popper from '@mui/material/Popper'
import { alpha } from '@mui/material/styles'
import visuallyHidden from '@mui/utils/visuallyHidden'
import { darkRoles, fontFamily, gray, shadow } from '../../theme/tokens'
import IconButton from '@mui/material/IconButton'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import AllInclusiveIcon from '@mui/icons-material/AllInclusive'
import MenuBookOutlinedIcon from '@mui/icons-material/MenuBookOutlined'
import SendIcon from '@mui/icons-material/Send'
import TextSnippetOutlinedIcon from '@mui/icons-material/TextSnippetOutlined'
import { CHAT_MAX_WIDTH } from '../../theme/theme'
import { useAuthStore } from '../../stores/authStore'
import { QUESTION_MAX_LENGTH, useChatStore } from '../../stores/chatStore'
import {
  metadataFilterScopeKey,
  useMetadataFilterOptionsStore,
} from '../../stores/metadataFilterOptionsStore'
import { useSpaceStore } from '../../stores/spaceStore'
import SpaceKnowledgeNotice from '../space/SpaceKnowledgeNotice'
import { spaceKnowledgeGap } from '../space/spaceKnowledge'
import MetadataFilterPopover from './MetadataFilterPopover'
import PromptCommandMenu from './PromptCommandMenu'
import { promptOptionId } from './promptCommand'
import PromptVariablesDialog from './PromptVariablesDialog'
import { usePromptCommand } from './usePromptCommand'
import type { SelectedPrompt } from './usePromptCommand'
import {
  dateChipLabel,
  formatFieldChipLabel,
  formatFieldLabel,
  libraryFieldChipLabel,
  libraryFieldLabel,
  withoutDateWindow,
  withoutDocumentTypes,
  withoutFormatField,
  withoutLibraryField,
} from './metadataFilterText'

/**
 * What became of a sent message; `restoreDraft` hands a refused question back to the input, and
 * `onRestored` is called once it is actually back there.
 */
export interface SendOutcome {
  restoreDraft?: string
  onRestored?: () => void
}

interface ChatInputProps {
  /** `usedPrompt` is the prompt the message was built from, absent when none is marked. */
  onSend: (
    message: string,
    usedPrompt?: SelectedPrompt,
  ) => void | SendOutcome | Promise<void | SendOutcome>
  /** Locks the whole input, typing included. */
  disabled?: boolean
  /**
   * An answer is still being generated: sending and changing the search scope are locked, so the
   * running answer keeps the scope it was asked with; typing the next question stays possible.
   */
  answerPending?: boolean
  /** A question refused while another chat was shown; put into the input if it is empty. */
  returnedQuestion?: SendOutcome | null
  /** Called once `returnedQuestion` has been handled, whether it was taken or not. */
  onReturnedQuestionHandled?: () => void
}

interface ActiveMention {
  /** Index of the '@' character that opened the mention in the current text value. */
  start: number
  /** Text typed after '@', used to filter suggestions. */
  query: string
}

/**
 * The chip bar's special entry: every knowledge library associated with the chat's space that the
 * person may read - never more (#560), always offered first.
 */
const SPACE_KNOWLEDGE_LABEL = 'Space-Wissen'

/** A knowledge library associated with the chat's space and readable by the person. */
interface SpaceLibrary {
  id: string
  name: string
}

type MentionSuggestion = { kind: 'all' } | { kind: 'library'; library: SpaceLibrary }

/**
 * The neutral hint under the input (#1920): the input triggers more than a question - prompts,
 * later agents and skills - so the line names the two prefixes instead of counting libraries.
 * What the next question searches is stated by the chip bar above it.
 */
const INPUT_HINT = '@ für Quellen, / für Aktionen'

/** The hint while an answer is pending: '@' changes the search scope and stays closed until then. */
const PENDING_INPUT_HINT = '/ für Aktionen'

/** A refused question goes back before whatever the person has typed since. */
function withRestoredQuestion(question: string, draft: string): string {
  return draft.trim() === '' ? question : `${question}\n\n${draft}`
}

/** From this share of {@link QUESTION_MAX_LENGTH} on, the input counts the characters. */
const LENGTH_COUNTER_THRESHOLD = 0.9

/**
 * The counter under the input, measured on the trimmed text that is actually sent; `null` while
 * the question is well below the limit.
 */
function lengthCounterText(length: number): string | null {
  if (length < QUESTION_MAX_LENGTH * LENGTH_COUNTER_THRESHOLD) return null
  const counted = `${length} von ${QUESTION_MAX_LENGTH} Zeichen`
  if (length <= QUESTION_MAX_LENGTH) return counted
  return `${counted} – bitte um ${length - QUESTION_MAX_LENGTH} Zeichen kürzen, damit sich die Frage senden lässt.`
}

/**
 * The screen reader announcement for the length: one fixed sentence per threshold state, so it
 * changes - and is announced - only when the question crosses 90 % or the limit, not per keystroke.
 */
function lengthAnnouncement(length: number): string {
  if (length > QUESTION_MAX_LENGTH) {
    return `Die Frage ist länger als ${QUESTION_MAX_LENGTH} Zeichen und lässt sich nicht senden.`
  }
  if (length >= QUESTION_MAX_LENGTH * LENGTH_COUNTER_THRESHOLD) {
    return `Die Frage nähert sich der Grenze von ${QUESTION_MAX_LENGTH} Zeichen und lässt sich senden.`
  }
  return ''
}

/**
 * Finds an in-progress '@' mention ending at the cursor, or null if none is active. Only
 * triggers when '@' starts a word (start of text or preceded by whitespace) and the fragment
 * typed so far contains no whitespace - typing a space closes the mention.
 */
function findActiveMention(text: string, cursor: number): ActiveMention | null {
  const upToCursor = text.slice(0, cursor)
  const atIndex = upToCursor.lastIndexOf('@')
  if (atIndex === -1) return null
  const charBefore = atIndex === 0 ? '' : text[atIndex - 1]
  if (charBefore && !/\s/.test(charBefore)) return null
  const query = upToCursor.slice(atIndex + 1)
  if (/\s/.test(query)) return null
  return { start: atIndex, query }
}

export default function ChatInput({
  onSend,
  disabled = false,
  answerPending = false,
  returnedQuestion = null,
  onReturnedQuestionHandled,
}: ChatInputProps) {
  const [value, setValue] = useState('')
  const [mention, setMention] = useState<ActiveMention | null>(null)
  // -1 means "nothing explicitly highlighted yet" - Enter only selects once ArrowDown/ArrowUp/
  // hover has set an index, so a bare '@' at the end of the text does not select the first
  // suggestion out from under the user on a plain Enter.
  const [highlightedIndex, setHighlightedIndex] = useState(-1)
  // Start index of a mention dismissed via Escape - suppresses reopening the list while the
  // cursor stays inside that same '@'-fragment, until the fragment is left (space, deletion past
  // '@', or a new '@' elsewhere).
  const [dismissedMentionStart, setDismissedMentionStart] = useState<number | null>(null)
  const inputRef = useRef<HTMLTextAreaElement>(null)
  const [inputBoxEl, setInputBoxEl] = useState<HTMLDivElement | null>(null)
  const wasDisabled = useRef(false)
  const mentionListboxId = useId()
  const promptListboxId = useId()
  const lengthCounterId = useId()
  const questionLength = value.trim().length
  const questionTooLong = questionLength > QUESTION_MAX_LENGTH
  const lengthCounter = lengthCounterText(questionLength)
  const sendLocked = disabled || answerPending
  // The running answer keeps the scope it was asked with.
  const scopeLocked = sendLocked

  // The chip bar is the only search-scope control (#560): "Durchsucht wird, was in der Leiste
  // steht." scope 'all' -> the special @Space-Wissen chip, 'libraries' -> concrete chips,
  // 'none' -> an emptied bar with a hint and a one-click way back to @Space-Wissen. Every chip
  // stays within the space's associated knowledge - the server enforces the same boundary.
  const scope = useChatStore((s) => s.scope)
  const setScopeAll = useChatStore((s) => s.setScopeAll)
  const referencedLibraryIds = useChatStore((s) => s.referencedLibraryIds)
  const addReferencedLibrary = useChatStore((s) => s.addReferencedLibrary)
  const removeReferencedLibrary = useChatStore((s) => s.removeReferencedLibrary)
  const clearScope = useChatStore((s) => s.clearScope)
  // #1070: the chat's sticky core-field filter, shown as removable chips next to the scope chips
  // and set through the popover - never derived from the question.
  const chatId = useChatStore((s) => s.chatId)
  const metadataFilter = useChatStore((s) => s.metadataFilter)
  const setMetadataFilter = useChatStore((s) => s.setMetadataFilter)
  const filterOptions = useMetadataFilterOptionsStore((s) => s.options)

  // '/' at the start of a line inserts a prompt: its text lands in the input, marked by a
  // removable chip; sending stays a separate action.
  const userName = useAuthStore((s) => s.user?.displayName ?? s.user?.email ?? '')
  const insertPromptText = useCallback((range: { start: number; end: number }, text: string) => {
    setValue((current) => `${current.slice(0, range.start)}${text}${current.slice(range.end)}`)
    const caret = range.start + text.length
    requestAnimationFrame(() => {
      inputRef.current?.focus()
      inputRef.current?.setSelectionRange(caret, caret)
    })
  }, [])
  const promptSpaceId = useChatStore((s) => s.spaceId)
  const promptCommand = usePromptCommand({
    spaceId: promptSpaceId,
    userName,
    insertText: insertPromptText,
  })

  // Adjusted during render (not in an effect) so the input already shows the text when it paints.
  const [seenReturned, setSeenReturned] = useState<SendOutcome | null>(null)
  const [returnedTaken, setReturnedTaken] = useState(false)
  if (returnedQuestion !== seenReturned) {
    setSeenReturned(returnedQuestion)
    const taken = !!returnedQuestion?.restoreDraft
    if (taken) setValue(withRestoredQuestion(returnedQuestion.restoreDraft!, value))
    setReturnedTaken(taken)
  }
  useEffect(() => {
    if (!returnedQuestion || returnedQuestion !== seenReturned) return
    if (returnedTaken) returnedQuestion.onRestored?.()
    onReturnedQuestionHandled?.()
  }, [returnedQuestion, seenReturned, returnedTaken, onReturnedQuestionHandled])

  // What the chip bar offers mirrors ChatService#effectiveLibraryScope: the space's associated
  // knowledge libraries the person may read, nothing else. Loaded per current chat's space via the
  // same spaceStore SpaceSettingsPage/SpacePage use (their routes never render at the same time as
  // this one) - but #783 review finding 1: that store write-back is asynchronous and per-space, so
  // this component must not simply trust whatever is currently in assetAssociations.
  // assetAssociationsSpaceId names which space that data actually describes;
  // isAssetAssociationsCurrent below is false while it does not match chatSpaceId - covering the
  // load still being in flight, a load that failed (spaceStore leaves it null rather than
  // defaulting to "no associations", #783 review nit 1), and the moment right after switching to a
  // chat in a different space, before its own load has even started.
  const chatSpaceId = useChatStore((s) => s.spaceId)
  const hasKnowledge = useSpaceStore((s) => s.hasKnowledge)
  const hasReadableKnowledge = useSpaceStore((s) => s.hasReadableKnowledge)
  const assetAssociations = useSpaceStore((s) => s.assetAssociations)
  const assetAssociationsSpaceId = useSpaceStore((s) => s.assetAssociationsSpaceId)
  const loadAssetAssociations = useSpaceStore((s) => s.loadAssetAssociations)
  const spaceRole = useSpaceStore(
    (s) => s.spaces.find((space) => space.id === chatSpaceId)?.userRole,
  )
  const isAssetAssociationsCurrent = assetAssociationsSpaceId === chatSpaceId
  const spaceLibraries = useMemo(
    (): SpaceLibrary[] =>
      isAssetAssociationsCurrent
        ? assetAssociations
            .filter((a) => a.assetType === 'KNOWLEDGE_LIBRARY')
            .map((a) => ({ id: a.assetId, name: a.name ?? '' }))
        : [],
    [assetAssociations, isAssetAssociationsCurrent],
  )
  const knowledgeGap =
    chatSpaceId && isAssetAssociationsCurrent
      ? spaceKnowledgeGap(hasKnowledge, hasReadableKnowledge)
      : null

  useEffect(() => {
    if (chatSpaceId) {
      void loadAssetAssociations(chatSpaceId)
    }
  }, [chatSpaceId, loadAssetAssociations])

  useEffect(() => {
    if (wasDisabled.current && !disabled) {
      inputRef.current?.focus()
    }
    wasDisabled.current = disabled
  }, [disabled])

  // One entry per referenced id, in the order the ids were added - not per matched library, so a
  // reference that is (still, or no longer) missing from the space's libraries keeps its own chip
  // instead of silently disappearing. An empty-looking bar for scope 'libraries' would otherwise
  // be indistinguishable from a deliberately emptied one (#564 review).
  const libraryChips = useMemo(() => {
    if (scope !== 'libraries') return []
    return referencedLibraryIds.map((libraryId) => {
      const library = spaceLibraries.find((l) => l.id === libraryId)
      if (library) return { kind: 'known' as const, libraryId, library }
      if (!isAssetAssociationsCurrent) return { kind: 'loading' as const, libraryId }
      // Known, and still not found - no longer associated, no longer readable or deleted.
      // Removable like any other chip, so a stale reference does not get stuck in the bar.
      return { kind: 'missing' as const, libraryId }
    })
  }, [spaceLibraries, isAssetAssociationsCurrent, referencedLibraryIds, scope])

  // While the space's associations are not known yet (still loading, or the load failed), the
  // @Space-Wissen chip must not quietly promise a scope; the line under the input says so. An
  // empty scope is stated by SpaceKnowledgeNotice above the chip bar.
  const scopeNotice =
    scope === 'all' && !isAssetAssociationsCurrent ? 'Suchbereich wird ermittelt …' : null

  // The scope the next question searches - the filter options are loaded for exactly this scope,
  // resolved server-side with the query's own rules: the chat's own settings, or for a chat not yet
  // created its space with the chip bar's settings.
  const filterScope = useMemo(
    () => ({
      chatId,
      spaceId: chatSpaceId,
      useKnowledge: scope === 'all',
      libraryIds: scope === 'libraries' ? referencedLibraryIds : [],
    }),
    [chatId, chatSpaceId, referencedLibraryIds, scope],
  )

  // A chat loaded with a Dokumentart condition needs the labels before the popover was ever
  // opened; loading the options for the current scope resolves them.
  const filterOptionsScopeKey = useMetadataFilterOptionsStore((s) => s.optionsScopeKey)
  const loadFilterOptions = useMetadataFilterOptionsStore((s) => s.loadOptions)
  const filterScopeKey = metadataFilterScopeKey(filterScope)
  const hasTypeFilter = (metadataFilter?.documentTypes ?? []).length > 0
  useEffect(() => {
    if (hasTypeFilter && filterOptionsScopeKey !== filterScopeKey) {
      void loadFilterOptions(filterScope)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [hasTypeFilter, filterOptionsScopeKey, filterScopeKey])

  const documentTypeChipLabel = useMemo(() => {
    const codes = metadataFilter?.documentTypes ?? []
    if (codes.length === 0) return undefined
    const labels = codes.map(
      (code) => filterOptions?.documentTypes.find((type) => type.code === code)?.label ?? code,
    )
    return `Dokumentart: ${labels.join(', ')}`
  }, [filterOptions, metadataFilter])
  const dateChip = metadataFilter ? dateChipLabel(metadataFilter) : undefined

  const removeDocumentTypeFilter = () => {
    if (metadataFilter) setMetadataFilter(withoutDocumentTypes(metadataFilter))
  }
  const removeDateFilter = () => {
    if (metadataFilter) setMetadataFilter(withoutDateWindow(metadataFilter))
  }

  const suggestions = useMemo((): MentionSuggestion[] => {
    if (mention === null) return []
    const query = mention.query.toLowerCase()
    const suggestions: MentionSuggestion[] = []
    // @Space-Wissen is always offered first (#560), regardless of the current scope - re-selecting
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

  const handleChange = (e: ChangeEvent<HTMLTextAreaElement>) => {
    const nextValue = e.target.value
    setValue(nextValue)
    const cursor = e.target.selectionStart ?? nextValue.length
    promptCommand.track(nextValue, cursor)
    // The '@' selection changes the search scope, so it stays closed while the scope is locked.
    const detected = scopeLocked ? null : findActiveMention(nextValue, cursor)
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

  const handleSend = () => {
    const trimmed = value.trim()
    if (sendLocked || !trimmed || trimmed.length > QUESTION_MAX_LENGTH || promptCommand.isInserting)
      return
    const outcome = promptCommand.selected
      ? onSend(trimmed, promptCommand.selected)
      : onSend(trimmed)
    setValue('')
    setDismissedMentionStart(null)
    closeMention()
    promptCommand.reset()
    // Sending by button would leave the focus on the button, which is locked from now on.
    inputRef.current?.focus()
    // A refused question comes back without its prompt, before any draft typed since, unless the
    // input is gone.
    void Promise.resolve(outcome).then((result) => {
      const restoreDraft = result?.restoreDraft
      if (!restoreDraft || !inputRef.current) return
      setValue((draft) => withRestoredQuestion(restoreDraft, draft))
      result.onRestored?.()
    })
  }

  const choosePrompt = (index: number) => {
    const entry = promptCommand.matches[index]
    if (!entry) return
    void promptCommand.choose(entry, inputRef.current?.selectionStart ?? value.length)
  }

  const handleKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    // While the '/' selection is open - loading, empty or failed alike - Enter never sends: the
    // '/' text is a command in the making, not a question. Escape turns it into plain text.
    if (promptCommand.isOpen) {
      if (e.key === 'Escape') {
        e.preventDefault()
        promptCommand.dismiss()
        return
      }
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault()
        promptCommand.moveHighlight(e.key === 'ArrowDown' ? 1 : -1)
        return
      }
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault()
        if (promptCommand.highlightedIndex >= 0) choosePrompt(promptCommand.highlightedIndex)
        return
      }
    }
    if (promptCommand.isInserting && e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      return
    }
    if (mention !== null) {
      if (e.key === 'Escape') {
        e.preventDefault()
        setDismissedMentionStart(mention.start)
        closeMention()
        return
      }
      if (suggestions.length > 0) {
        if (e.key === 'ArrowDown') {
          e.preventDefault()
          setHighlightedIndex((i) => (i + 1 >= suggestions.length ? 0 : i + 1))
          return
        }
        if (e.key === 'ArrowUp') {
          e.preventDefault()
          setHighlightedIndex((i) => (i - 1 < 0 ? suggestions.length - 1 : i - 1))
          return
        }
        if (e.key === 'Enter' && highlightedIndex >= 0) {
          e.preventDefault()
          selectSuggestion(suggestions[highlightedIndex])
          return
        }
      }
    }

    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSend()
    }
  }

  // Drives the Popper itself - it opens for both the suggestion list and the "no match" message.
  const mentionOpen = mention !== null
  // aria-expanded/aria-controls must only claim an open *listbox* while that listbox is actually
  // rendered - suggestions.length === 0 renders a plain "no match" message instead, with no
  // element carrying mentionListboxId (review finding #539).
  const mentionListOpen = mentionOpen && suggestions.length > 0
  const highlightedOptionId =
    highlightedIndex >= 0 ? `${mentionListboxId}-option-${highlightedIndex}` : undefined
  // The '/' listbox is rendered only while it has options and no load error - the same honesty
  // rule as the '@' listbox above.
  const promptListOpen =
    promptCommand.isOpen && promptCommand.error === null && promptCommand.matches.length > 0
  const comboboxExpanded = mentionListOpen || promptListOpen
  const comboboxControls = promptListOpen
    ? promptListboxId
    : mentionListOpen
      ? mentionListboxId
      : undefined
  const activeDescendant = promptListOpen
    ? promptOptionId(promptListboxId, promptCommand.highlightedIndex)
    : mentionListOpen
      ? highlightedOptionId
      : undefined

  return (
    <Box sx={{ flexShrink: 0, p: 2, bgcolor: 'background.default' }}>
      {knowledgeGap && chatSpaceId && (
        <Box sx={{ maxWidth: CHAT_MAX_WIDTH, mx: 'auto', mb: 1 }}>
          <SpaceKnowledgeNotice spaceId={chatSpaceId} gap={knowledgeGap} role={spaceRole} />
        </Box>
      )}
      <Box
        sx={{
          maxWidth: CHAT_MAX_WIDTH,
          mx: 'auto',
          mb: 1,
          display: 'flex',
          flexWrap: 'wrap',
          alignItems: 'center',
          gap: 0.75,
        }}
      >
        {promptCommand.selected && (
          <Chip
            icon={<TextSnippetOutlinedIcon />}
            label={`Prompt: ${promptCommand.selected.title}`}
            size="small"
            variant="outlined"
            onDelete={disabled ? undefined : promptCommand.clearSelected}
            aria-label={`Kennzeichnung Prompt: ${promptCommand.selected.title} entfernen`}
            data-testid="used-prompt-chip"
          />
        )}
        {scope === 'all' && (
          <Chip
            icon={<AllInclusiveIcon />}
            label="@Space-Wissen"
            size="small"
            color="primary"
            onDelete={scopeLocked ? undefined : clearScope}
            // The default delete icon carries aria-hidden from MUI, so the accessible name has to
            // sit on the chip itself rather than on that icon (review finding #539).
            aria-label="Referenz Space-Wissen entfernen"
          />
        )}
        {scope === 'libraries' &&
          libraryChips.map((chip) => {
            if (chip.kind === 'known') {
              return (
                <Chip
                  key={chip.libraryId}
                  label={chip.library.name}
                  size="small"
                  variant="filled"
                  color="primary"
                  onDelete={scopeLocked ? undefined : () => removeReferencedLibrary(chip.libraryId)}
                  aria-label={`Bibliotheksreferenz ${chip.library.name} entfernen`}
                />
              )
            }
            if (chip.kind === 'loading') {
              return (
                <Chip
                  key={chip.libraryId}
                  label="Bibliothek wird geladen …"
                  size="small"
                  variant="outlined"
                  disabled
                  aria-label="Bibliotheksreferenz wird geladen"
                />
              )
            }
            return (
              <Chip
                key={chip.libraryId}
                label="Nicht verfügbare Bibliothek"
                size="small"
                variant="outlined"
                color="warning"
                onDelete={scopeLocked ? undefined : () => removeReferencedLibrary(chip.libraryId)}
                aria-label="Nicht verfügbare Bibliotheksreferenz entfernen"
              />
            )
          })}
        {scope === 'none' && (
          <>
            {/* #697 review, Befund 2: warning.main auf heller Fläche unterschreitet 4,5:1 (docs/design/accessibility.md
                2.4) - text.secondary erfüllt den Kontrast in beiden Schemata; die Aussage ist an sich schon eine
                Warnung, sie braucht keine zusätzliche Signalfarbe, um verstanden zu werden. */}
            <Typography variant="caption" sx={{ color: 'text.secondary' }}>
              Antwortet ohne Dokumente.
            </Typography>
            <Chip
              icon={<AllInclusiveIcon />}
              label="@Space-Wissen nutzen"
              size="small"
              variant="outlined"
              onClick={scopeLocked ? undefined : setScopeAll}
              disabled={scopeLocked}
              aria-label="Wieder das Wissen des Space durchsuchen"
            />
          </>
        )}
        {scope !== 'none' && (
          <>
            {documentTypeChipLabel && (
              <Chip
                label={documentTypeChipLabel}
                size="small"
                variant="outlined"
                color="secondary"
                onDelete={scopeLocked ? undefined : removeDocumentTypeFilter}
                aria-label="Filter nach Dokumentart entfernen"
                data-testid="metadata-filter-chip-document-type"
              />
            )}
            {dateChip && (
              <Chip
                label={dateChip}
                size="small"
                variant="outlined"
                color="secondary"
                onDelete={scopeLocked ? undefined : removeDateFilter}
                aria-label="Filter nach Datum entfernen"
                data-testid="metadata-filter-chip-document-date"
              />
            )}
            {(metadataFilter?.libraryFields ?? []).map((condition) => (
              <Chip
                key={`${condition.libraryId}/${condition.fieldKey}`}
                label={libraryFieldChipLabel(condition, filterOptions)}
                size="small"
                variant="outlined"
                color="secondary"
                onDelete={
                  scopeLocked
                    ? undefined
                    : () => {
                        if (metadataFilter)
                          setMetadataFilter(withoutLibraryField(metadataFilter, condition))
                      }
                }
                aria-label={`Filter nach ${libraryFieldLabel(condition, filterOptions)} entfernen`}
                data-testid="metadata-filter-chip-library-field"
              />
            ))}
            {(metadataFilter?.formatFields ?? []).map((condition) => (
              <Chip
                key={condition.fieldKey}
                label={formatFieldChipLabel(condition, filterOptions)}
                size="small"
                variant="outlined"
                color="secondary"
                onDelete={
                  scopeLocked
                    ? undefined
                    : () => {
                        if (metadataFilter)
                          setMetadataFilter(withoutFormatField(metadataFilter, condition))
                      }
                }
                aria-label={`Filter nach ${formatFieldLabel(condition, filterOptions)} entfernen`}
                data-testid="metadata-filter-chip-format-field"
              />
            ))}
            <MetadataFilterPopover
              scope={filterScope}
              filter={metadataFilter}
              onChange={setMetadataFilter}
              disabled={scopeLocked}
            />
          </>
        )}
      </Box>

      <Box
        ref={setInputBoxEl}
        sx={{
          display: 'flex',
          alignItems: 'flex-end',
          gap: 1,
          maxWidth: CHAT_MAX_WIDTH,
          mx: 'auto',
          bgcolor: 'background.paper',
          border: 1,
          // Mockup 1a draws the input row with the crisper gray-300 line and a hairline
          // shadow (#658).
          borderColor: (theme) =>
            theme.palette.mode === 'dark' ? darkRoles.borderStrong : gray[300],
          borderRadius: '10px',
          boxShadow: shadow.hairline,
          px: 1.75,
          py: 1.25,
        }}
      >
        <TextField
          fullWidth
          multiline
          maxRows={6}
          placeholder="Nachricht eingeben …"
          value={value}
          onChange={handleChange}
          onKeyDown={handleKeyDown}
          disabled={disabled}
          inputRef={inputRef}
          variant="standard"
          slotProps={{
            htmlInput: {
              role: 'combobox',
              'aria-expanded': comboboxExpanded,
              'aria-haspopup': 'listbox',
              'aria-controls': comboboxControls,
              'aria-autocomplete': 'list',
              'aria-activedescendant': activeDescendant,
              'aria-invalid': questionTooLong,
              'aria-describedby': lengthCounter ? lengthCounterId : undefined,
            },
          }}
          sx={{
            '& .MuiInputBase-root': {
              borderRadius: 0,
              background: 'none',
              px: 1.5,
              py: 1,
              alignItems: 'flex-start',
              '&::before, &::after': { display: 'none' },
            },
            '& textarea': {
              overflowY: 'auto !important',
              resize: 'none',
            },
          }}
        />
        {/* #1920: a send symbol, not "Fragen" - the input triggers more than questions. */}
        <IconButton
          color="primary"
          onClick={handleSend}
          disabled={sendLocked || !value.trim() || questionTooLong || promptCommand.isInserting}
          aria-label="Senden"
          sx={{
            alignSelf: 'flex-end',
            bgcolor: 'primary.main',
            color: 'primary.contrastText',
            borderRadius: '8px',
            p: 1,
            '&:hover': { bgcolor: 'primary.dark' },
            '&.Mui-disabled': { bgcolor: 'action.disabledBackground', color: 'action.disabled' },
          }}
        >
          <SendIcon sx={{ fontSize: 18 }} />
        </IconButton>
      </Box>

      <Popper
        open={mentionOpen}
        anchorEl={inputBoxEl}
        placement="top-start"
        style={{ zIndex: 1300, width: inputBoxEl?.offsetWidth }}
        modifiers={[{ name: 'offset', options: { offset: [0, 8] } }]}
      >
        <ClickAwayListener onClickAway={closeMention}>
          <Paper elevation={4} sx={{ maxHeight: 280, overflowY: 'auto' }}>
            {/* Mockup 1h (#591): mono eyebrow head, book icon, typed prefix in bold, and a
                type badge on the right - built to take agents as a second kind later. */}
            <Typography
              component="div"
              sx={{
                px: 1.5,
                py: 1,
                fontFamily: fontFamily.mono,
                fontSize: 9.5,
                letterSpacing: '0.08em',
                textTransform: 'uppercase',
                color: 'text.secondary',
                borderBottom: 1,
                borderColor: 'divider',
              }}
            >
              Suchbereich eingrenzen
            </Typography>
            {suggestions.length > 0 ? (
              <List id={mentionListboxId} role="listbox" aria-label="Suchbereich" dense>
                {suggestions.map((suggestion, index) => {
                  const key = suggestion.kind === 'all' ? '@all-knowledge' : suggestion.library.id
                  const name = suggestion.kind === 'all' ? '@Space-Wissen' : suggestion.library.name
                  const badge =
                    suggestion.kind === 'all'
                      ? 'Wissen des Space · hebt Eingrenzung auf'
                      : 'Bibliothek · verengt die Suche'
                  const query = mention?.query ?? ''
                  const matchIndex =
                    query.length > 0 ? name.toLowerCase().indexOf(query.toLowerCase()) : -1
                  return (
                    <ListItemButton
                      key={key}
                      id={`${mentionListboxId}-option-${index}`}
                      role="option"
                      aria-selected={index === highlightedIndex}
                      selected={index === highlightedIndex}
                      onMouseDown={(e) => e.preventDefault()}
                      onMouseEnter={() => setHighlightedIndex(index)}
                      onClick={() => selectSuggestion(suggestion)}
                      sx={{
                        gap: 1.25,
                        py: 1,
                        '&.Mui-selected': {
                          bgcolor: (theme) => alpha(theme.palette.primary.main, 0.1),
                        },
                      }}
                    >
                      {suggestion.kind === 'all' ? (
                        <AllInclusiveIcon sx={{ fontSize: 15, color: 'text.primary' }} />
                      ) : (
                        <MenuBookOutlinedIcon sx={{ fontSize: 15, color: 'text.primary' }} />
                      )}
                      <Typography
                        component="span"
                        noWrap
                        sx={{ flex: 1, fontSize: 13.5, color: 'text.primary' }}
                      >
                        {matchIndex >= 0 ? (
                          <>
                            {name.slice(0, matchIndex)}
                            <Box component="strong" sx={{ fontWeight: 600 }}>
                              {name.slice(matchIndex, matchIndex + query.length)}
                            </Box>
                            {name.slice(matchIndex + query.length)}
                          </>
                        ) : (
                          name
                        )}
                      </Typography>
                      <Typography
                        component="span"
                        sx={{
                          flex: 'none',
                          fontSize: 10.5,
                          color: 'text.secondary',
                          border: 1,
                          borderColor: 'divider',
                          borderRadius: '4px',
                          px: 1,
                          py: 0.25,
                        }}
                      >
                        {badge}
                      </Typography>
                    </ListItemButton>
                  )
                })}
              </List>
            ) : (
              <Typography variant="body2" sx={{ color: 'text.secondary', p: 1.5 }}>
                Keine passende Bibliothek in diesem Space
              </Typography>
            )}
          </Paper>
        </ClickAwayListener>
      </Popper>

      <PromptCommandMenu
        open={promptCommand.isOpen}
        anchorEl={inputBoxEl}
        listboxId={promptListboxId}
        prompts={promptCommand.matches}
        hasPrompts={promptCommand.hasPrompts}
        highlightedIndex={promptCommand.highlightedIndex}
        isLoading={promptCommand.isLoading}
        error={promptCommand.error}
        onHighlight={promptCommand.setHighlightedIndex}
        onSelect={(entry) => choosePrompt(promptCommand.matches.indexOf(entry))}
        onClose={promptCommand.close}
      />
      {promptCommand.pendingForm && (
        <PromptVariablesDialog
          prompt={promptCommand.pendingForm}
          userName={userName}
          onCancel={promptCommand.cancelForm}
          onInsert={promptCommand.completeForm}
        />
      )}

      {/* Mockup 1a (#591): the quiet line under the input, neutral since #1920. */}
      <Box sx={{ maxWidth: CHAT_MAX_WIDTH, mx: 'auto', mt: 0.875 }}>
        <Typography component="div" sx={{ fontSize: 12, color: 'text.secondary' }}>
          {scopeNotice ?? (answerPending ? PENDING_INPUT_HINT : INPUT_HINT)}
        </Typography>
        {lengthCounter && (
          <Typography
            id={lengthCounterId}
            component="div"
            sx={{ fontSize: 12, color: questionTooLong ? 'error.main' : 'text.secondary' }}
          >
            {lengthCounter}
          </Typography>
        )}
        <Box component="div" aria-live="polite" sx={visuallyHidden}>
          {lengthAnnouncement(questionLength)}
        </Box>
      </Box>
    </Box>
  )
}
