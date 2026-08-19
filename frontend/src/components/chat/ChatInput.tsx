import type { ChangeEvent, KeyboardEvent } from 'react'
import { useEffect, useId, useMemo, useRef, useState } from 'react'
import Box from '@mui/material/Box'
import Chip from '@mui/material/Chip'
import FormControlLabel from '@mui/material/FormControlLabel'
import IconButton from '@mui/material/IconButton'
import List from '@mui/material/List'
import ListItemButton from '@mui/material/ListItemButton'
import ListItemText from '@mui/material/ListItemText'
import ClickAwayListener from '@mui/material/ClickAwayListener'
import Paper from '@mui/material/Paper'
import Popper from '@mui/material/Popper'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import SendIcon from '@mui/icons-material/Send'
import { CHAT_MAX_WIDTH } from '../../theme/theme'
import { useChatStore } from '../../stores/chatStore'
import { useLibraryStore } from '../../stores/libraryStore'

interface ChatInputProps {
  onSend: (message: string) => void
  disabled?: boolean
}

interface ActiveMention {
  /** Index of the '@' character that opened the mention in the current text value. */
  start: number
  /** Text typed after '@', used to filter suggestions. */
  query: string
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

export default function ChatInput({ onSend, disabled = false }: ChatInputProps) {
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

  const useKnowledge = useChatStore((s) => s.useKnowledge)
  const setUseKnowledge = useChatStore((s) => s.setUseKnowledge)
  const referencedLibraryIds = useChatStore((s) => s.referencedLibraryIds)
  const addReferencedLibrary = useChatStore((s) => s.addReferencedLibrary)
  const removeReferencedLibrary = useChatStore((s) => s.removeReferencedLibrary)

  const libraries = useLibraryStore((s) => s.libraries)
  const loadLibraries = useLibraryStore((s) => s.loadLibraries)

  useEffect(() => {
    if (libraries.length === 0) {
      void loadLibraries()
    }
  }, [libraries.length, loadLibraries])

  useEffect(() => {
    if (wasDisabled.current && !disabled) {
      inputRef.current?.focus()
    }
    wasDisabled.current = disabled
  }, [disabled])

  const referencedLibraries = useMemo(
    () => libraries.filter((library) => referencedLibraryIds.includes(library.id)),
    [libraries, referencedLibraryIds],
  )

  const suggestions = useMemo(() => {
    if (mention === null) return []
    const query = mention.query.toLowerCase()
    return libraries
      .filter((library) => !referencedLibraryIds.includes(library.id))
      .filter((library) => library.name.toLowerCase().includes(query))
      .slice(0, 8)
  }, [libraries, mention, referencedLibraryIds])

  /** Closes the suggestion popup without recording a dismissal (used on selection/send). */
  const closeMention = () => {
    setMention(null)
    setHighlightedIndex(-1)
  }

  const selectSuggestion = (libraryId: string) => {
    if (mention === null) return
    const cursor = inputRef.current?.selectionStart ?? value.length
    const before = value.slice(0, mention.start)
    const after = value.slice(cursor)
    const nextValue = `${before}${after}`
    setValue(nextValue)
    addReferencedLibrary(libraryId)
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
    const detected = findActiveMention(nextValue, cursor)
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
    if (!trimmed) return
    onSend(trimmed)
    setValue('')
    setDismissedMentionStart(null)
    closeMention()
  }

  const handleKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
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
          selectSuggestion(suggestions[highlightedIndex].id)
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

  return (
    <Box sx={{ flexShrink: 0, p: 2, bgcolor: 'background.default' }}>
      {referencedLibraries.length > 0 && (
        <Tooltip
          title={
            useKnowledge
              ? 'Diese Referenzen wirken erst, wenn „Wissen nutzen" ausgeschaltet ist.'
              : 'Nur diese Bibliotheken werden durchsucht.'
          }
        >
          <Box
            sx={{
              maxWidth: CHAT_MAX_WIDTH,
              mx: 'auto',
              mb: 1,
              display: 'flex',
              flexWrap: 'wrap',
              gap: 0.75,
            }}
          >
            {referencedLibraries.map((library) => (
              <Chip
                key={library.id}
                label={library.name}
                size="small"
                variant={useKnowledge ? 'outlined' : 'filled'}
                color={useKnowledge ? 'default' : 'primary'}
                onDelete={disabled ? undefined : () => removeReferencedLibrary(library.id)}
                // The default delete icon carries aria-hidden from MUI, so the accessible name
                // has to sit on the chip itself rather than on that icon (review finding #539).
                aria-label={`Bibliotheksreferenz ${library.name} entfernen`}
              />
            ))}
          </Box>
        </Tooltip>
      )}

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
          borderColor: 'divider',
          borderRadius: '16px',
          p: 1,
        }}
      >
        <TextField
          fullWidth
          multiline
          maxRows={6}
          placeholder="Stellen Sie eine Frage …"
          value={value}
          onChange={handleChange}
          onKeyDown={handleKeyDown}
          disabled={disabled}
          inputRef={inputRef}
          variant="standard"
          slotProps={{
            htmlInput: {
              role: 'combobox',
              'aria-expanded': mentionListOpen,
              'aria-haspopup': 'listbox',
              'aria-controls': mentionListOpen ? mentionListboxId : undefined,
              'aria-autocomplete': 'list',
              'aria-activedescendant': mentionListOpen ? highlightedOptionId : undefined,
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
        <IconButton
          color="primary"
          onClick={handleSend}
          disabled={disabled || !value.trim()}
          aria-label="Nachricht senden"
          sx={{
            bgcolor: 'primary.main',
            color: 'white',
            '&:hover': { bgcolor: 'primary.dark' },
            '&.Mui-disabled': { bgcolor: 'action.disabledBackground' },
            borderRadius: '12px',
          }}
        >
          <SendIcon />
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
          <Paper elevation={4} sx={{ maxHeight: 240, overflowY: 'auto' }}>
            {suggestions.length > 0 ? (
              <List id={mentionListboxId} role="listbox" aria-label="Wissensbibliotheken" dense>
                {suggestions.map((library, index) => (
                  <ListItemButton
                    key={library.id}
                    id={`${mentionListboxId}-option-${index}`}
                    role="option"
                    aria-selected={index === highlightedIndex}
                    selected={index === highlightedIndex}
                    onMouseDown={(e) => e.preventDefault()}
                    onMouseEnter={() => setHighlightedIndex(index)}
                    onClick={() => selectSuggestion(library.id)}
                  >
                    <ListItemText
                      primary={library.name}
                      secondary={library.description ?? undefined}
                    />
                  </ListItemButton>
                ))}
              </List>
            ) : (
              <Typography variant="body2" color="text.secondary" sx={{ p: 1.5 }}>
                Keine passende Bibliothek gefunden
              </Typography>
            )}
          </Paper>
        </ClickAwayListener>
      </Popper>

      <Box
        sx={{
          maxWidth: CHAT_MAX_WIDTH,
          mx: 'auto',
          mt: 0.75,
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
        }}
      >
        <FormControlLabel
          control={
            <Switch
              size="small"
              checked={useKnowledge}
              onChange={(e) => setUseKnowledge(e.target.checked)}
              disabled={disabled}
            />
          }
          label={<Typography variant="body2">Wissen nutzen</Typography>}
        />
        <Typography variant="caption" color="text.secondary">
          Enter zum Senden, Umschalt+Enter für eine neue Zeile
        </Typography>
      </Box>

      {!useKnowledge && referencedLibraryIds.length === 0 && (
        <Typography
          variant="caption"
          color="warning.main"
          sx={{ display: 'block', maxWidth: CHAT_MAX_WIDTH, mx: 'auto', mt: 0.5 }}
        >
          Ohne referenzierte Bibliotheken antwortet die KI ohne Wissensbasis. Mit @ eine Bibliothek
          referenzieren.
        </Typography>
      )}
    </Box>
  )
}
