import type { ChangeEvent, ClipboardEvent, DragEvent, KeyboardEvent } from 'react'
import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import Box from '@mui/material/Box'
import List from '@mui/material/List'
import ClickAwayListener from '@mui/material/ClickAwayListener'
import Paper from '@mui/material/Paper'
import Popper from '@mui/material/Popper'
import { alpha } from '@mui/material/styles'
import visuallyHidden from '@mui/utils/visuallyHidden'
import { darkRoles, fontFamily, fontSize, gray, iconSize, shadow } from '@/shared/theme/tokens'
import IconButton from '@mui/material/IconButton'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import AttachFileIcon from '@mui/icons-material/AttachFile'
import SendIcon from '@mui/icons-material/Send'
import StopIcon from '@mui/icons-material/Stop'
import { CHAT_MAX_WIDTH, focusFrameStyle } from '@/shared/theme/theme'
import type { ChatAttachmentRef } from '@/shared/api/types'
import { useAuthStore } from '@/shared/session/authStore'
import { useFeatureEnabled } from '@/shared/session/useFeatureEnabled'
import { ARRIVAL_FOCUS_ATTRIBUTE } from '@/shared/ui/a11y/routeFocus'
import { QUESTION_MAX_LENGTH, useChatStore } from '../stores/chatStore'
import { SpaceKnowledgeNotice } from '@/features/space'
import { useChatAttachmentDraft } from '../hooks/useChatAttachmentDraft'
import { AiNotice } from './AiNotice'
import { ChatAttachmentDraftChips } from './ChatAttachmentChips'
import ChatActionMenu from './ChatActionMenu'
import ChatScopeChipBar from './ChatScopeChipBar'
import DictationControl from './DictationControl'
import DictationField from './DictationField'
import { useChatDictation } from './useChatDictation'
import { withDictatedText } from './dictationText'
import { actionOptionId, activeChatActionSources } from './chatActions'
import type { ChatActionMark, ChatActionSource } from './chatActions'
import { CHAT_ACTION_SOURCES } from './chatActionSources'
import { useChatActions } from './useChatActions'
import { useChatMetadataFilterChips } from './useChatMetadataFilterChips'
import { useMentionSuggestions } from './useMentionSuggestions'
import { useChatAgentChoice } from './useChatAgentChoice'
import MentionOption from './MentionOption'
import { mentionGroups, mentionKey } from './mentionSuggestionText'
import { useNarrowField } from './useNarrowField'
import { useSpaceLibraryScope } from './useSpaceLibraryScope'
import {
  COMPACT_PLACEHOLDER_WIDTH,
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
  /** The question's agent comes back with the draft. */
  keepAgent?: boolean
}

/** The agent a question is sent to; it replaces the question's search scope. */
export interface ChosenAgent {
  id: string
  name: string
}

interface ChatInputProps {
  /**
   * `usedPrompt` is the prompt the message was built from, absent when none is marked;
   * `attachments` are the ready files sent along, absent when there are none.
   */
  onSend: (
    message: string,
    usedPrompt?: ChatActionMark,
    attachments?: ChatAttachmentRef[],
    agent?: ChosenAgent,
  ) => void | SendOutcome | Promise<void | SendOutcome>
  /** Locks the whole input, typing included. */
  disabled?: boolean
  /**
   * An answer is still being generated: sending and changing the search scope are locked, so the
   * running answer keeps the scope it was asked with; typing the next question stays possible.
   */
  answerPending?: boolean
  /** Ends the pending answer; while one is pending, the send button turns into this one. */
  onCancelAnswer?: () => void
  /** A question refused while another chat was shown; put into the input if it is empty. */
  returnedQuestion?: SendOutcome | null
  /** Called once `returnedQuestion` has been handled, whether it was taken or not. */
  onReturnedQuestionHandled?: () => void
  /**
   * What the '/' menu collects; by default every registered source. A source behind a
   * switched-off feature contributes nothing either way (ADR-0044).
   */
  actionSources?: readonly ChatActionSource[]
  /**
   * Takes the focus whenever this changes to a value other than null, and marks the input as the
   * page's arrival focus target meanwhile - a new chat opens ready for its first question.
   */
  focusRequest?: string | null
  /** The AI notice line under the input; off where the surface shows the notice already. */
  showAiNotice?: boolean
}

export default function ChatInput({
  onSend,
  disabled = false,
  answerPending = false,
  onCancelAnswer,
  returnedQuestion = null,
  onReturnedQuestionHandled,
  actionSources = CHAT_ACTION_SOURCES,
  focusRequest = null,
  showAiNotice = true,
}: ChatInputProps) {
  const [value, setValue] = useState('')
  const mountedRef = useRef(true)
  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
    }
  }, [])
  const inputRef = useRef<HTMLTextAreaElement>(null)
  const [inputBoxEl, setInputBoxEl] = useState<HTMLDivElement | null>(null)
  const wasDisabled = useRef(false)
  const mentionListboxId = useId()
  const actionListboxId = useId()
  const lengthCounterId = useId()
  const hintId = useId()
  const [fieldEl, setFieldEl] = useState<HTMLDivElement | null>(null)
  const compactPlaceholder = useNarrowField(fieldEl, COMPACT_PLACEHOLDER_WIDTH)
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

  // Files dropped, pasted or picked go up at once and are read while the question is written;
  // the question waits until every one of them is ready or removed.
  const uploadsEnabled = useFeatureEnabled('chatUpload') && actionSpaceId !== null
  const draft = useChatAttachmentDraft(uploadsEnabled ? actionSpaceId : null)
  const attachmentsPending = draft.busy || draft.blocked
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [dragActive, setDragActive] = useState(false)
  const carriesFiles = (e: DragEvent) => Array.from(e.dataTransfer?.types ?? []).includes('Files')
  const handleDragOver = (e: DragEvent<HTMLDivElement>) => {
    if (!uploadsEnabled || disabled || !carriesFiles(e)) return
    e.preventDefault()
    e.dataTransfer.dropEffect = 'copy'
    if (!dragActive) setDragActive(true)
  }
  const handleDragLeave = (e: DragEvent<HTMLDivElement>) => {
    if (e.currentTarget.contains(e.relatedTarget as Node | null)) return
    setDragActive(false)
  }
  const handleDrop = (e: DragEvent<HTMLDivElement>) => {
    if (!uploadsEnabled || disabled || !carriesFiles(e)) return
    e.preventDefault()
    setDragActive(false)
    draft.add(Array.from(e.dataTransfer.files))
  }
  const handlePaste = (e: ClipboardEvent<HTMLDivElement>) => {
    const files = Array.from(e.clipboardData?.files ?? [])
    // Office puts a picture of copied text beside the text itself: text wins over that picture.
    const carriesText = Array.from(e.clipboardData?.types ?? []).includes('text/plain')
    if (!uploadsEnabled || files.length === 0 || carriesText) return
    e.preventDefault()
    draft.add(files)
  }
  const handleFilesPicked = (e: ChangeEvent<HTMLInputElement>) => {
    draft.add(Array.from(e.target.files ?? []))
    e.target.value = ''
  }

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
  const { agents, agent, chooseAgent, clearAgent } = useChatAgentChoice(chatSpaceId)
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
    agents,
    chosenAgentId: agent?.id ?? null,
    chooseAgent,
  })

  useEffect(() => {
    if (wasDisabled.current && !disabled) {
      inputRef.current?.focus()
    }
    wasDisabled.current = disabled
  }, [disabled])

  useEffect(() => {
    if (focusRequest !== null) inputRef.current?.focus()
  }, [focusRequest])

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

  const handleSend = (text: string = value) => {
    const trimmed = text.trim()
    if (
      sendLocked ||
      !trimmed ||
      trimmed.length > QUESTION_MAX_LENGTH ||
      actions.isInserting ||
      attachmentsPending
    )
      return false
    // Only a mark its source sends as the prompt reaches the question; any other stays a chip.
    const selected = actions.selected
    const mark =
      selected?.source.sendsMarkAsUsedPrompt === true
        ? { id: selected.mark.id, title: selected.mark.title }
        : undefined
    const attachments = draft.take()
    const chosen = agent ? { id: agent.id, name: agent.name } : undefined
    const outcome = chosen
      ? onSend(trimmed, mark, attachments, chosen)
      : attachments.length > 0
        ? onSend(trimmed, mark, attachments)
        : mark
          ? onSend(trimmed, mark)
          : onSend(trimmed)
    clearAgent()
    setValue('')
    resetMention()
    actions.reset()
    // Sending by button would leave the focus on the button, which is locked from now on.
    inputRef.current?.focus()
    // A refused question comes back without its prompt, before any draft typed since, unless the
    // input is gone; a running dictation keeps it in the draft above the recording.
    void Promise.resolve(outcome).then((result) => {
      const restoreDraft = result?.restoreDraft
      if (!restoreDraft || !mountedRef.current) return
      setValue((current) => withRestoredQuestion(restoreDraft, current))
      draft.restore(attachments)
      if (chosen && result.keepAgent) chooseAgent(chosen)
      result.onRestored?.()
    })
    return true
  }

  // Dictation fills the input; only the send button sends, during a recording with its final text.
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
  const dictation = useChatDictation({ onText: appendDictatedText, onError: setDictationError })
  // Send during a recording: the final text joins the draft, and the send itself runs once the
  // field is back, with the state of that moment - a question that cannot go out then (too long,
  // answer running, upload pending) stays in the field.
  const sendAfterDictationRef = useRef(false)
  const sendDictation = async () => {
    const text = await dictation.stop(false)
    if (!text) return
    sendAfterDictationRef.current = true
    setValue((current) => withDictatedText(current, text))
  }
  const wasDictatingRef = useRef(false)
  useEffect(() => {
    const wasDictating = wasDictatingRef.current
    wasDictatingRef.current = dictation.busy
    if (dictation.busy) return
    if (sendAfterDictationRef.current) {
      sendAfterDictationRef.current = false
      handleSend()
    } else if (wasDictating) {
      // Discarded, failed or taken over: the field is back and gets the focus, unless the person
      // has moved on to another part of the page meanwhile.
      const active = document.activeElement
      if (!active || active === document.body || inputBoxEl?.contains(active)) {
        inputRef.current?.focus()
      }
    }
  })

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
    <Box
      sx={{ flexShrink: 0, p: 2, bgcolor: 'background.default' }}
      onDragOver={handleDragOver}
      onDragLeave={handleDragLeave}
      onDrop={handleDrop}
    >
      {knowledgeGap && chatSpaceId && (
        <Box sx={{ maxWidth: CHAT_MAX_WIDTH, mx: 'auto', mb: 1 }}>
          <SpaceKnowledgeNotice spaceId={chatSpaceId} gap={knowledgeGap} role={spaceRole} />
        </Box>
      )}
      <ChatScopeChipBar
        selected={actions.selected}
        onClearSelected={actions.clearSelected}
        agentName={agent?.name ?? null}
        onClearAgent={clearAgent}
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
      <ChatAttachmentDraftChips
        items={draft.items}
        notice={draft.notice}
        onRemove={draft.remove}
        disabled={disabled}
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
          // The design draws the input row with the crisper gray-300 line and a hairline
          // shadow.
          borderColor: theme.palette.mode === 'dark' ? darkRoles.borderStrong : gray[300],
          borderRadius: '10px',
          boxShadow: shadow.hairline,
          px: 1.75,
          py: 1.25,
          // The message field itself is borderless; the row's frame carries its focus - only
          // the field's, a focused button of the row shows its own ring instead.
          '&:has(textarea:focus)': focusFrameStyle(theme.palette.primary.main),
          ...(dragActive && {
            borderStyle: 'dashed',
            borderColor: theme.palette.primary.main,
            bgcolor: alpha(theme.palette.primary.main, 0.06),
          }),
        })}
      >
        {dictation.busy ? (
          <DictationField dictation={dictation} draft={value} />
        ) : (
          <TextField
            ref={setFieldEl}
            fullWidth
            multiline
            maxRows={6}
            placeholder={inputPlaceholder(answerPending, actions.enabled, compactPlaceholder)}
            value={value}
            onChange={handleChange}
            onKeyDown={handleKeyDown}
            onPaste={handlePaste}
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
                [ARRIVAL_FOCUS_ATTRIBUTE]: focusRequest !== null ? '' : undefined,
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
        )}
        {uploadsEnabled && !dictation.busy && (
          <>
            <input
              ref={fileInputRef}
              type="file"
              multiple
              hidden
              data-testid="chat-attachment-input"
              onChange={handleFilesPicked}
            />
            <IconButton
              onClick={() => fileInputRef.current?.click()}
              disabled={disabled}
              aria-label="Dateien anhängen"
              sx={{ alignSelf: 'flex-end', p: 1, color: 'text.secondary' }}
            >
              <AttachFileIcon sx={{ fontSize: iconSize.md }} />
            </IconButton>
          </>
        )}
        <DictationControl dictation={dictation} disabled={disabled} />
        {/* Ends the pending answer; the send button beside it stays locked until it is over. */}
        {answerPending && onCancelAnswer && (
          <IconButton
            onClick={onCancelAnswer}
            aria-label="Antwort abbrechen"
            sx={{
              alignSelf: 'flex-end',
              border: 1,
              borderColor: 'primary.main',
              color: 'primary.main',
              borderRadius: '8px',
              p: 1,
            }}
          >
            <StopIcon sx={{ fontSize: iconSize.md }} />
          </IconButton>
        )}
        {/* A send symbol, not "Fragen" - the input triggers more than questions. */}
        <IconButton
          color="primary"
          onClick={() => (dictation.status === 'recording' ? void sendDictation() : handleSend())}
          disabled={
            sendLocked ||
            dictation.status === 'processing' ||
            (!value.trim() && dictation.status !== 'recording') ||
            questionTooLong ||
            actions.isInserting ||
            attachmentsPending
          }
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
          <SendIcon sx={{ fontSize: iconSize.md }} />
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
            {/* Mono eyebrow head, book icon, typed prefix in bold, and a
                type badge on the right - built to take agents as a second kind later. */}
            <Typography
              component="div"
              sx={{
                px: 1.5,
                py: 1,
                fontFamily: fontFamily.mono,
                fontSize: fontSize.label,
                letterSpacing: '0.08em',
                textTransform: 'uppercase',
                color: 'text.secondary',
                borderBottom: 1,
                borderColor: 'divider',
              }}
            >
              {agents.length > 0 ? 'Wissen oder Agent wählen' : 'Suchbereich eingrenzen'}
            </Typography>
            {suggestions.length > 0 ? (
              <List
                id={mentionListboxId}
                role="listbox"
                aria-label={agents.length > 0 ? 'Suchbereich oder Agent' : 'Suchbereich'}
                dense
              >
                {mentionGroups(suggestions).map((group, groupIndex) => (
                  <Box component="li" role="presentation" key={group.kind} sx={{ p: 0 }}>
                    {/* With agents on offer each group gets a visible heading of its own. */}
                    {agents.length > 0 && (
                      <Typography
                        id={`${mentionListboxId}-${group.kind}`}
                        component="div"
                        sx={{
                          px: 1.5,
                          pt: 1,
                          pb: 0.5,
                          fontFamily: fontFamily.mono,
                          fontSize: fontSize.label,
                          letterSpacing: '0.08em',
                          textTransform: 'uppercase',
                          color: 'text.secondary',
                          ...(groupIndex > 0 ? { borderTop: 1, borderColor: 'divider' } : {}),
                        }}
                      >
                        {group.kind === 'agent' ? 'Agenten' : 'Wissen'}
                      </Typography>
                    )}
                    <Box
                      component="ul"
                      role="group"
                      {...(agents.length > 0
                        ? { 'aria-labelledby': `${mentionListboxId}-${group.kind}` }
                        : { 'aria-label': 'Wissen' })}
                      sx={{ listStyle: 'none', m: 0, p: 0 }}
                    >
                      {group.items.map(({ suggestion, index }) => (
                        <MentionOption
                          key={mentionKey(suggestion)}
                          suggestion={suggestion}
                          optionId={`${mentionListboxId}-option-${index}`}
                          highlighted={index === highlightedIndex}
                          query={mention?.query ?? ''}
                          onHighlight={() => setHighlightedIndex(index)}
                          onSelect={() => selectSuggestion(suggestion)}
                        />
                      ))}
                    </Box>
                  </Box>
                ))}
              </List>
            ) : (
              <Typography variant="body2" sx={{ color: 'text.secondary', p: 1.5 }}>
                {agents.length > 0
                  ? 'Keine passende Bibliothek und kein passender Agent in diesem Space'
                  : 'Keine passende Bibliothek in diesem Space'}
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

      {/* The quiet, neutral line under the input. */}
      <Box sx={{ maxWidth: CHAT_MAX_WIDTH, mx: 'auto', mt: 0.875 }}>
        {/* The placeholder vanishes while typing; the description keeps the hint reachable. */}
        {hint && (
          <Box component="span" id={hintId} sx={visuallyHidden}>
            {hint}
          </Box>
        )}
        {scopeNotice && (
          <Typography component="div" sx={{ fontSize: fontSize.meta, color: 'text.secondary' }}>
            {scopeNotice}
          </Typography>
        )}
        {showAiNotice && <AiNotice variant="line" />}
        {dictationError && (
          <Typography
            component="div"
            role="alert"
            sx={{ fontSize: fontSize.meta, color: 'error.main' }}
          >
            {dictationError}
          </Typography>
        )}
        {lengthCounter && (
          <Typography
            id={lengthCounterId}
            component="div"
            sx={{
              fontSize: fontSize.meta,
              color: questionTooLong ? 'error.main' : 'text.secondary',
            }}
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
