import type { ChangeEvent, KeyboardEvent } from 'react'
import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import Box from '@mui/material/Box'
import List from '@mui/material/List'
import ListItemButton from '@mui/material/ListItemButton'
import ClickAwayListener from '@mui/material/ClickAwayListener'
import Paper from '@mui/material/Paper'
import Popper from '@mui/material/Popper'
import { alpha, useTheme } from '@mui/material/styles'
import useMediaQuery from '@mui/material/useMediaQuery'
import visuallyHidden from '@mui/utils/visuallyHidden'
import { darkRoles, fontFamily, gray, shadow } from '@/shared/theme/tokens'
import IconButton from '@mui/material/IconButton'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import AllInclusiveIcon from '@mui/icons-material/AllInclusive'
import MenuBookOutlinedIcon from '@mui/icons-material/MenuBookOutlined'
import SendIcon from '@mui/icons-material/Send'
import { CHAT_MAX_WIDTH, focusFrameStyle } from '@/shared/theme/theme'
import { useAuthStore } from '@/shared/session/authStore'
import { QUESTION_MAX_LENGTH, useChatStore } from '../stores/chatStore'
import { SpaceKnowledgeNotice } from '@/features/space'
import { AiNotice } from './AiNotice'
import ChatActionMenu from './ChatActionMenu'
import ChatScopeChipBar from './ChatScopeChipBar'
import DictationControl from './DictationControl'
import { withDictatedText } from './dictationText'
import { actionOptionId, activeChatActionSources } from './chatActions'
import type { ChatActionMark, ChatActionSource } from './chatActions'
import { CHAT_ACTION_SOURCES } from './chatActionSources'
import { useChatActions } from './useChatActions'
import { useChatMetadataFilterChips } from './useChatMetadataFilterChips'
import { useMentionSuggestions } from './useMentionSuggestions'
import { useSpaceLibraryScope } from './useSpaceLibraryScope'
import {
  INPUT_LABEL,
  inputHint,
  inputPlaceholder,
  lengthAnnouncement,
  lengthCounterText,
  withRestoredQuestion,
} from './chatInputText'

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
    usedPrompt?: ChatActionMark,
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
  /**
   * What the '/' menu collects; by default every registered source. A source behind a
   * switched-off feature contributes nothing either way (ADR-0044).
   */
  actionSources?: readonly ChatActionSource[]
}

export default function ChatInput({
  onSend,
  disabled = false,
  answerPending = false,
  returnedQuestion = null,
  onReturnedQuestionHandled,
  actionSources = CHAT_ACTION_SOURCES,
}: ChatInputProps) {
  const [value, setValue] = useState('')
  const inputRef = useRef<HTMLTextAreaElement>(null)
  const [inputBoxEl, setInputBoxEl] = useState<HTMLDivElement | null>(null)
  const wasDisabled = useRef(false)
  const mentionListboxId = useId()
  const actionListboxId = useId()
  const lengthCounterId = useId()
  const hintId = useId()
  const muiTheme = useTheme()
  const compactPlaceholder = useMediaQuery(muiTheme.breakpoints.down('sm'))
  const questionLength = value.trim().length
  const questionTooLong = questionLength > QUESTION_MAX_LENGTH
  const lengthCounter = lengthCounterText(questionLength)
  const sendLocked = disabled || answerPending
  // The running answer keeps the scope it was asked with.
  const scopeLocked = sendLocked

  // The chip bar is the only search-scope control: "Durchsucht wird, was in der Leiste
  // steht." scope 'all' -> the special @Space-Wissen chip, 'libraries' -> concrete chips,
  // 'none' -> an emptied bar with a hint and a one-click way back to @Space-Wissen. Every chip
  // stays within the space's associated knowledge - the server enforces the same boundary.
  const scope = useChatStore((s) => s.scope)
  const setScopeAll = useChatStore((s) => s.setScopeAll)
  const referencedLibraryIds = useChatStore((s) => s.referencedLibraryIds)
  const addReferencedLibrary = useChatStore((s) => s.addReferencedLibrary)
  const removeReferencedLibrary = useChatStore((s) => s.removeReferencedLibrary)
  const clearScope = useChatStore((s) => s.clearScope)
  // The chat's sticky core-field filter, shown as removable chips next to the scope chips
  // and set through the popover - never derived from the question.
  const chatId = useChatStore((s) => s.chatId)
  const metadataFilter = useChatStore((s) => s.metadataFilter)
  const setMetadataFilter = useChatStore((s) => s.setMetadataFilter)

  // '/' at the start of a line opens the actions of every active source - a prompt, for one: its
  // text lands in the input, marked by a removable chip; sending stays a separate action.
  const userName = useAuthStore((s) => s.user?.displayName ?? s.user?.email ?? '')
  const features = useAuthStore((s) => s.features)
  const activeSources = useMemo(
    () => activeChatActionSources(actionSources, features),
    [actionSources, features],
  )
  const insertActionText = useCallback((range: { start: number; end: number }, text: string) => {
    setValue((current) => `${current.slice(0, range.start)}${text}${current.slice(range.end)}`)
    const caret = range.start + text.length
    requestAnimationFrame(() => {
      inputRef.current?.focus()
      inputRef.current?.setSelectionRange(caret, caret)
    })
  }, [])
  const actionSpaceId = useChatStore((s) => s.spaceId)
  const actions = useChatActions({
    sources: activeSources,
    spaceId: actionSpaceId,
    userName,
    insertText: insertActionText,
  })
  const hint = inputHint(answerPending, actions.enabled)

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

  const { chatSpaceId, spaceRole, spaceLibraries, knowledgeGap, libraryChips, scopeNotice } =
    useSpaceLibraryScope(scope, referencedLibraryIds)
  const {
    mention,
    highlightedIndex,
    setHighlightedIndex,
    suggestions,
    closeMention,
    selectSuggestion,
    trackMention,
    dismissMention,
    resetMention,
  } = useMentionSuggestions({
    value,
    setValue,
    inputRef,
    spaceLibraries,
    scope,
    referencedLibraryIds,
    setScopeAll,
    addReferencedLibrary,
  })

  useEffect(() => {
    if (wasDisabled.current && !disabled) {
      inputRef.current?.focus()
    }
    wasDisabled.current = disabled
  }, [disabled])

  const {
    filterScope,
    metadataEnabled,
    filterOptions,
    documentTypeChipLabel,
    dateChip,
    removeDocumentTypeFilter,
    removeDateFilter,
  } = useChatMetadataFilterChips({
    chatId,
    chatSpaceId,
    scope,
    referencedLibraryIds,
    metadataFilter,
    setMetadataFilter,
  })

  const handleChange = (e: ChangeEvent<HTMLTextAreaElement>) => {
    const nextValue = e.target.value
    setValue(nextValue)
    const cursor = e.target.selectionStart ?? nextValue.length
    actions.track(nextValue, cursor)
    trackMention(nextValue, cursor, scopeLocked)
  }

  const handleSend = () => {
    const trimmed = value.trim()
    if (sendLocked || !trimmed || trimmed.length > QUESTION_MAX_LENGTH || actions.isInserting)
      return
    // Only a mark its source sends as the prompt reaches the question; any other stays a chip.
    const selected = actions.selected
    const outcome =
      selected?.source.sendsMarkAsUsedPrompt === true
        ? onSend(trimmed, { id: selected.mark.id, title: selected.mark.title })
        : onSend(trimmed)
    setValue('')
    resetMention()
    actions.reset()
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

  // Dictation only ever fills the input; sending stays the person's own action.
  const [dictationError, setDictationError] = useState<string | null>(null)
  const appendDictatedText = useCallback((text: string) => {
    setValue((draft) => withDictatedText(draft, text))
    requestAnimationFrame(() => {
      const input = inputRef.current
      if (!input) return
      input.focus()
      input.setSelectionRange(input.value.length, input.value.length)
    })
  }, [])

  const chooseAction = (index: number) => {
    const entry = actions.matches[index]
    if (!entry) return
    void actions.choose(entry, inputRef.current?.selectionStart ?? value.length)
  }

  const handleKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    // While the '/' selection is open - loading, empty or failed alike - Enter never sends: the
    // '/' text is a command in the making, not a question. Escape turns it into plain text.
    if (actions.isOpen) {
      if (e.key === 'Escape') {
        e.preventDefault()
        actions.dismiss()
        return
      }
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault()
        actions.moveHighlight(e.key === 'ArrowDown' ? 1 : -1)
        return
      }
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault()
        if (actions.highlightedIndex >= 0) chooseAction(actions.highlightedIndex)
        return
      }
    }
    if (actions.isInserting && e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      return
    }
    if (mention !== null) {
      if (e.key === 'Escape') {
        e.preventDefault()
        dismissMention()
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
  // element carrying mentionListboxId.
  const mentionListOpen = mentionOpen && suggestions.length > 0
  const highlightedOptionId =
    highlightedIndex >= 0 ? `${mentionListboxId}-option-${highlightedIndex}` : undefined
  // The '/' listbox is rendered only while it has options and no load error - the same honesty
  // rule as the '@' listbox above.
  const actionListOpen = actions.isOpen && actions.error === null && actions.matches.length > 0
  const comboboxExpanded = mentionListOpen || actionListOpen
  const comboboxControls = actionListOpen
    ? actionListboxId
    : mentionListOpen
      ? mentionListboxId
      : undefined
  const activeDescendant = actionListOpen
    ? actionOptionId(actionListboxId, actions.highlightedIndex)
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
      <ChatScopeChipBar
        selected={actions.selected}
        onClearSelected={actions.clearSelected}
        disabled={disabled}
        scope={scope}
        scopeLocked={scopeLocked}
        libraryChips={libraryChips}
        onClearScope={clearScope}
        onSetScopeAll={setScopeAll}
        onRemoveLibrary={removeReferencedLibrary}
        metadataEnabled={metadataEnabled}
        metadataFilter={metadataFilter}
        filterOptions={filterOptions}
        filterScope={filterScope}
        onMetadataFilterChange={setMetadataFilter}
        documentTypeChipLabel={documentTypeChipLabel}
        dateChip={dateChip}
        onRemoveDocumentTypeFilter={removeDocumentTypeFilter}
        onRemoveDateFilter={removeDateFilter}
      />

      <Box
        ref={setInputBoxEl}
        sx={(theme) => ({
          display: 'flex',
          alignItems: 'flex-end',
          gap: 1,
          maxWidth: CHAT_MAX_WIDTH,
          mx: 'auto',
          bgcolor: 'background.paper',
          border: 1,
          // Mockup 1a draws the input row with the crisper gray-300 line and a hairline
          // shadow.
          borderColor: theme.palette.mode === 'dark' ? darkRoles.borderStrong : gray[300],
          borderRadius: '10px',
          boxShadow: shadow.hairline,
          px: 1.75,
          py: 1.25,
          // The message field itself is borderless; the row's frame carries its focus - only
          // the field's, a focused button of the row shows its own ring instead.
          '&:has(textarea:focus)': focusFrameStyle(theme.palette.primary.main),
        })}
      >
        <TextField
          fullWidth
          multiline
          maxRows={6}
          placeholder={inputPlaceholder(answerPending, actions.enabled, compactPlaceholder)}
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
              'aria-label': INPUT_LABEL,
              'aria-describedby':
                [hint ? hintId : null, lengthCounter ? lengthCounterId : null]
                  .filter((id) => id !== null)
                  .join(' ') || undefined,
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
            // The placeholder carries the prefix hint: text.secondary at full opacity keeps 4.5:1.
            '& textarea::placeholder': { color: 'text.secondary', opacity: 1 },
          }}
        />
        <DictationControl
          onText={appendDictatedText}
          onError={setDictationError}
          disabled={disabled}
        />
        {/* A send symbol, not "Fragen" - the input triggers more than questions. */}
        <IconButton
          color="primary"
          onClick={handleSend}
          disabled={sendLocked || !value.trim() || questionTooLong || actions.isInserting}
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
            {/* Mockup 1h: mono eyebrow head, book icon, typed prefix in bold, and a
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

      <ChatActionMenu
        open={actions.isOpen}
        anchorEl={inputBoxEl}
        listboxId={actionListboxId}
        actions={actions.matches}
        hasActions={actions.hasOptions}
        texts={actions.texts}
        highlightedIndex={actions.highlightedIndex}
        isLoading={actions.isLoading}
        error={actions.error}
        onHighlight={actions.setHighlightedIndex}
        onSelect={(entry) => chooseAction(actions.matches.indexOf(entry))}
        onClose={actions.close}
      />
      {actions.pendingForm?.({ onInsert: actions.completeForm, onCancel: actions.cancelForm })}

      {/* Mockup 1a: the quiet, neutral line under the input. */}
      <Box sx={{ maxWidth: CHAT_MAX_WIDTH, mx: 'auto', mt: 0.875 }}>
        {/* The placeholder vanishes while typing; the description keeps the hint reachable. */}
        {hint && (
          <Box component="span" id={hintId} sx={visuallyHidden}>
            {hint}
          </Box>
        )}
        {scopeNotice && (
          <Typography component="div" sx={{ fontSize: 12, color: 'text.secondary' }}>
            {scopeNotice}
          </Typography>
        )}
        <AiNotice variant="line" />
        {dictationError && (
          <Typography component="div" role="alert" sx={{ fontSize: 12, color: 'error.main' }}>
            {dictationError}
          </Typography>
        )}
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
