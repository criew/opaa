import type { ChangeEvent, KeyboardEvent } from 'react'
import { useEffect, useId, useMemo, useRef, useState } from 'react'
import Box from '@mui/material/Box'
import Chip from '@mui/material/Chip'
import List from '@mui/material/List'
import ListItemButton from '@mui/material/ListItemButton'
import ClickAwayListener from '@mui/material/ClickAwayListener'
import Paper from '@mui/material/Paper'
import Popper from '@mui/material/Popper'
import { alpha } from '@mui/material/styles'
import { darkRoles, fontFamily, gray, shadow } from '../../theme/tokens'
import Button from '@mui/material/Button'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import AllInclusiveIcon from '@mui/icons-material/AllInclusive'
import MenuBookOutlinedIcon from '@mui/icons-material/MenuBookOutlined'
import { CHAT_MAX_WIDTH } from '../../theme/theme'
import { useChatStore } from '../../stores/chatStore'
import { useLibraryStore } from '../../stores/libraryStore'
import {
  metadataFilterScopeKey,
  useMetadataFilterOptionsStore,
} from '../../stores/metadataFilterOptionsStore'
import { useSpaceStore } from '../../stores/spaceStore'
import type { LibraryListResponse } from '../../types/api'
import MetadataFilterPopover from './MetadataFilterPopover'
import {
  dateChipLabel,
  formatFieldChipLabel,
  libraryFieldChipLabel,
  withoutDateWindow,
  withoutDocumentTypes,
  withoutFormatField,
  withoutLibraryField,
} from './metadataFilterText'

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

/** The chip bar's special entry: matches every readable library (#560), always offered first. */
const ALL_KNOWLEDGE_LABEL = 'Alles-Wissen'

type MentionSuggestion = { kind: 'all' } | { kind: 'library'; library: LibraryListResponse }

// #782/#783: the scope line under the input either renders as "Durchsucht: <text>" ('summary') or
// replaces that whole line with a standalone sentence ('notice') - see the scopeLine memo below for
// which case is which.
type ScopeLine = { kind: 'summary'; text: string } | { kind: 'notice'; text: string }

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

  // The chip bar is the only search-scope control (#560): "Durchsucht wird, was in der Leiste
  // steht." scope 'all' -> the special @Alles-Wissen chip, 'libraries' -> concrete chips,
  // 'none' -> an emptied bar with a hint and a one-click way back to @Alles-Wissen.
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

  const libraries = useLibraryStore((s) => s.libraries)
  const librariesLoading = useLibraryStore((s) => s.isLoading)
  const loadLibraries = useLibraryStore((s) => s.loadLibraries)

  useEffect(() => {
    if (libraries.length === 0) {
      void loadLibraries()
    }
  }, [libraries.length, loadLibraries])

  // #782: @Alles-Wissen's scope line must mirror ChatService#effectiveLibraryScope, not just
  // "every readable library" - a space curated via space<->library associations (#706) narrows
  // the actual search to associated ∩ readable, and the line has to say so, not the wider number
  // the user never gets to search. Loaded per current chat's space via the same spaceStore
  // SpaceManagementPage/SpacePage use (their routes never render at the same time as this one, so
  // there is no simultaneous-consumer conflict) - but #783 review finding 1: that store write-back
  // is asynchronous and per-space, so this component must not simply trust whatever is currently in
  // libraryAssociations/hasLibraryAssociations. libraryAssociationsSpaceId names which space that
  // data actually describes; isLibraryAssociationsCurrent below is false while it does not match
  // chatSpaceId - covering the load still being in flight, a load that failed (spaceStore leaves it
  // null rather than defaulting to "no associations", #783 review nit 1), and the moment right after
  // switching to a chat in a different space, before its own load has even started.
  const chatSpaceId = useChatStore((s) => s.spaceId)
  const hasLibraryAssociations = useSpaceStore((s) => s.hasLibraryAssociations)
  const libraryAssociations = useSpaceStore((s) => s.libraryAssociations)
  const libraryAssociationsSpaceId = useSpaceStore((s) => s.libraryAssociationsSpaceId)
  const loadLibraryAssociations = useSpaceStore((s) => s.loadLibraryAssociations)
  const isLibraryAssociationsCurrent = libraryAssociationsSpaceId === chatSpaceId

  useEffect(() => {
    if (chatSpaceId) {
      void loadLibraryAssociations(chatSpaceId)
    }
  }, [chatSpaceId, loadLibraryAssociations])

  useEffect(() => {
    if (wasDisabled.current && !disabled) {
      inputRef.current?.focus()
    }
    wasDisabled.current = disabled
  }, [disabled])

  // One entry per referenced id, in the order the ids were added - not per matched library, so a
  // reference that is (still, or no longer) missing from the loaded list keeps its own chip
  // instead of silently disappearing. An empty-looking bar for scope 'libraries' would otherwise
  // be indistinguishable from a deliberately emptied one (#564 review).
  const libraryChips = useMemo(() => {
    if (scope !== 'libraries') return []
    return referencedLibraryIds.map((libraryId) => {
      const library = libraries.find((l) => l.id === libraryId)
      if (library) return { kind: 'known' as const, libraryId, library }
      if (librariesLoading) return { kind: 'loading' as const, libraryId }
      // Loaded, and still not found - either no longer readable or deleted. Removable like any
      // other chip, so a stale reference does not get stuck in the bar.
      return { kind: 'missing' as const, libraryId }
    })
  }, [libraries, librariesLoading, referencedLibraryIds, scope])

  // Mockup 1a's quiet scope line (#591), narrowed for #782: says what the next question will
  // actually search - ChatService#effectiveLibraryScope's own rule (docs/features/spaces-and-
  // assets.md#suchbereich-je-chatart). A space *with* library associations narrows @Alles-Wissen to
  // associated ∩ readable, so the line counts that intersection (readableByCaller on each
  // association) and calls it "zugeordnet [...] lesbar", not just "zugeordnet" - a CURATOR/ADMIN can
  // see associations they cannot themselves read (#706), so "zugeordnet" alone would silently omit
  // the readability narrowing and look inconsistent with SpacePage's own count for the same space
  // (#783 review nit 4). A space *without* any association still falls back to every readable
  // library, unchanged from before #782.
  //
  // 'summary' renders as "Durchsucht: <text>"; 'notice' replaces that whole line with a standalone
  // sentence - used for the two cases a bare number cannot honestly represent: the associated∩
  // readable scope being empty (#783 review nit 3, matching the wording MessageBubble/SpacePage
  // already use for the same state) and the associations for the current space not being known yet
  // (still loading, or the load failed - #783 review nit 1: must never default to "every readable
  // library", which is exactly the false claim #782 fixed).
  const scopeLine = useMemo((): ScopeLine => {
    if (scope === 'none') {
      return { kind: 'summary', text: 'nichts — antwortet ohne Wissensbasis' }
    }
    if (scope === 'libraries') {
      // #783 review, "vorbestehend": ChatService#effectiveLibraryScope intersects referenced ids
      // with the readable libraries too (ChatService.java:249-251) - only the 'known' chips (found
      // in the readable `libraries` list) survive that intersection, exactly like 'missing' chips
      // already mark an id that is not (or no longer) readable.
      const count = referencedLibraryIds.filter((id) => libraries.some((l) => l.id === id)).length
      return {
        kind: 'summary',
        text: count === 1 ? '1 gewählter Bestand' : `${count} gewählte Bestände`,
      }
    }
    if (!isLibraryAssociationsCurrent) {
      return { kind: 'notice', text: 'Suchbereich wird ermittelt …' }
    }
    if (hasLibraryAssociations) {
      const count = libraryAssociations.filter((a) => a.readableByCaller).length
      if (count === 0) {
        return {
          kind: 'notice',
          text: 'In diesem Space ist für Sie derzeit kein Wissen verfügbar.',
        }
      }
      return {
        kind: 'summary',
        text:
          count === 1 ? '1 zugeordneter lesbarer Bestand' : `${count} zugeordnete lesbare Bestände`,
      }
    }
    if (libraries.length === 1) return { kind: 'summary', text: '1 lesbarer Bestand' }
    if (libraries.length > 1) {
      return { kind: 'summary', text: `${libraries.length} lesbare Bestände` }
    }
    return { kind: 'summary', text: 'alle lesbaren Bestände' }
  }, [
    hasLibraryAssociations,
    isLibraryAssociationsCurrent,
    libraries,
    libraryAssociations,
    referencedLibraryIds,
    scope,
  ])

  // The scope the next question searches - the filter options are loaded for exactly this scope,
  // resolved server-side with the query's own rules (chat first, otherwise useKnowledge/ids).
  const filterScope = useMemo(
    () => ({
      chatId,
      useKnowledge: scope === 'all',
      libraryIds: scope === 'libraries' ? referencedLibraryIds : [],
    }),
    [chatId, referencedLibraryIds, scope],
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
    // @Alles-Wissen is always offered first (#560), regardless of the current scope - re-selecting
    // it while already active is a harmless no-op, and it is the only way back once removed.
    if (ALL_KNOWLEDGE_LABEL.toLowerCase().includes(query)) {
      suggestions.push({ kind: 'all' })
    }
    const alreadyReferenced = scope === 'libraries' ? referencedLibraryIds : []
    libraries
      .filter((library) => !alreadyReferenced.includes(library.id))
      .filter((library) => library.name.toLowerCase().includes(query))
      .slice(0, 8 - suggestions.length)
      .forEach((library) => suggestions.push({ kind: 'library', library }))
    return suggestions
  }, [libraries, mention, referencedLibraryIds, scope])

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

  return (
    <Box sx={{ flexShrink: 0, p: 2, bgcolor: 'background.default' }}>
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
        {scope === 'all' && (
          <Chip
            icon={<AllInclusiveIcon />}
            label="@Alles-Wissen"
            size="small"
            color="primary"
            onDelete={disabled ? undefined : clearScope}
            // The default delete icon carries aria-hidden from MUI, so the accessible name has to
            // sit on the chip itself rather than on that icon (review finding #539).
            aria-label="Referenz Alles-Wissen entfernen"
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
                  onDelete={disabled ? undefined : () => removeReferencedLibrary(chip.libraryId)}
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
                onDelete={disabled ? undefined : () => removeReferencedLibrary(chip.libraryId)}
                aria-label="Nicht verfügbare Bibliotheksreferenz entfernen"
              />
            )
          })}
        {scope === 'none' && (
          <>
            {/* #697 review, Befund 2: warning.main auf heller Fläche unterschreitet 4,5:1 (docs/design/accessibility.md
                2.4) - text.secondary erfüllt den Kontrast in beiden Schemata; die Aussage ist an sich schon eine
                Warnung, sie braucht keine zusätzliche Signalfarbe, um verstanden zu werden. */}
            <Typography variant="caption" color="text.secondary">
              Antwortet ohne Dokumente.
            </Typography>
            <Chip
              icon={<AllInclusiveIcon />}
              label="@Alles-Wissen nutzen"
              size="small"
              variant="outlined"
              onClick={disabled ? undefined : setScopeAll}
              disabled={disabled}
              aria-label="Wieder alles Wissen durchsuchen"
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
                onDelete={disabled ? undefined : removeDocumentTypeFilter}
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
                onDelete={disabled ? undefined : removeDateFilter}
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
                  disabled
                    ? undefined
                    : () => {
                        if (metadataFilter)
                          setMetadataFilter(withoutLibraryField(metadataFilter, condition))
                      }
                }
                aria-label={`Filter nach ${condition.fieldKey} entfernen`}
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
                  disabled
                    ? undefined
                    : () => {
                        if (metadataFilter)
                          setMetadataFilter(withoutFormatField(metadataFilter, condition))
                      }
                }
                aria-label={`Filter nach ${condition.fieldKey} entfernen`}
                data-testid="metadata-filter-chip-format-field"
              />
            ))}
            <MetadataFilterPopover
              scope={filterScope}
              filter={metadataFilter}
              onChange={setMetadataFilter}
              disabled={disabled}
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
          placeholder="Frage stellen … mit @ auf eine Quelle eingrenzen"
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
        <Button
          variant="contained"
          onClick={handleSend}
          disabled={disabled || !value.trim()}
          aria-label="Nachricht senden"
        >
          Fragen
        </Button>
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
                  const name = suggestion.kind === 'all' ? '@Alles-Wissen' : suggestion.library.name
                  const badge =
                    suggestion.kind === 'all'
                      ? 'Alles Wissen · hebt Eingrenzung auf'
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
              <Typography variant="body2" color="text.secondary" sx={{ p: 1.5 }}>
                Keine passende Bibliothek gefunden
              </Typography>
            )}
          </Paper>
        </ClickAwayListener>
      </Popper>

      {/* Mockup 1a (#591): the quiet scope line under the input. */}
      <Box sx={{ maxWidth: CHAT_MAX_WIDTH, mx: 'auto', mt: 0.875 }}>
        <Typography component="div" sx={{ fontSize: 12, color: 'text.secondary' }}>
          {scopeLine.kind === 'summary' ? (
            <>
              Durchsucht:{' '}
              <Box component="span" sx={{ fontWeight: 500 }}>
                {scopeLine.text}
              </Box>
              {scope === 'all' && ' · mit @ auf eine Quelle eingrenzen'}
            </>
          ) : (
            scopeLine.text
          )}
        </Typography>
      </Box>
    </Box>
  )
}
