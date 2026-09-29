import { useEffect, useId, useMemo, useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import FormControlLabel from '@mui/material/FormControlLabel'
import InputAdornment from '@mui/material/InputAdornment'
import Stack from '@mui/material/Stack'
import Tab from '@mui/material/Tab'
import Tabs from '@mui/material/Tabs'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { alpha } from '@mui/material/styles'
import SearchIcon from '@mui/icons-material/Search'
import { useLocation, useNavigate, useParams } from 'react-router'
import PageHeading from '../components/a11y/PageHeading'
import { ListEmptyState, ListLoading, ListPager } from '../components/admin/list/AdminList'
import ChatSearchResults from '../components/chat/ChatSearchResults'
import ChatTable from '../components/chat/ChatTable'
import { chatTitle, splitChats } from '../components/chat/chatListSections'
import {
  CHAT_SEARCH_MAX_LENGTH,
  CHAT_SEARCH_MIN_LENGTH,
  isSearchableTerm,
  useChatSearch,
} from '../hooks/useChatSearch'
import type { ChatSearchHandover } from '../hooks/useChatSearch'
import { CHAT_PAGE_SIZE, useChatListStore } from '../stores/chatListStore'
import { confirmAction } from '../stores/confirmStore'
import { useSpaceStore } from '../stores/spaceStore'
import type { ChatBulkAction, ChatSummary } from '../types/api'

type ChatsTab = 'active' | 'archive'

function handedOverTerm(state: unknown): string | null {
  if (typeof state !== 'object' || state === null) return null
  const term = (state as Partial<ChatSearchHandover>).chatSearchTerm
  return typeof term === 'string' ? term : null
}

const EMPTY_SELECTION: ReadonlySet<string> = new Set()

function chatCount(count: number): string {
  return count === 1 ? '1 Chat' : `${count} Chats`
}

const RESULT_VERBS: Record<ChatBulkAction, string> = {
  ARCHIVE: 'archiviert',
  UNARCHIVE: 'aus dem Archiv zurückgeholt',
  DELETE: 'gelöscht',
}

/**
 * The management surface of the person's own chats in one space (docs/features/chat-list.md,
 * "Die Seite ‚Chats‘ je Space"): the active chats and the person's chat archive as tabs, in the
 * table of the administration lists, with a row menu for single actions and multi-selection for
 * bulk ones. A click on a title opens the chat. While a term is entered, the chat search's hits
 * take the place of the tabs.
 */
export default function ChatsPage() {
  const { spaceId = '' } = useParams<{ spaceId: string }>()
  const navigate = useNavigate()
  const location = useLocation()
  const spaces = useSpaceStore((s) => s.spaces)
  const loadSpaces = useSpaceStore((s) => s.loadSpaces)
  const space = spaces.find((candidate) => candidate.id === spaceId)
  const chats = useChatListStore((s) => s.chatsBySpaceId[spaceId])
  const archive = useChatListStore((s) => s.archiveBySpaceId[spaceId])
  const isLoading = useChatListStore((s) => s.isLoading)
  const isLoadingArchive = useChatListStore((s) => s.isLoadingArchive)
  const error = useChatListStore((s) => s.error)
  const loadChats = useChatListStore((s) => s.loadChats)
  const loadArchivedChats = useChatListStore((s) => s.loadArchivedChats)
  const applyBulkAction = useChatListStore((s) => s.applyBulkAction)
  const setChatPinned = useChatListStore((s) => s.setChatPinned)

  const [tab, setTab] = useState<ChatsTab>('active')
  const [activePage, setActivePage] = useState(0)
  const [selection, setSelection] = useState<ReadonlySet<string>>(EMPTY_SELECTION)
  const [statusMessage, setStatusMessage] = useState('')
  const [isBusy, setIsBusy] = useState(false)
  const idPrefix = useId()
  const search = useChatSearch(spaceId)
  const searchFieldRef = useRef<HTMLInputElement>(null)
  const isSearching = search.query.trim() !== ''
  const selectAllRef = useRef<HTMLInputElement>(null)
  // The action buttons vanish while a bulk action runs and once the selection is gone, which
  // drops their focus; it is placed back onto "select all" (or onto the tab) once the list settled.
  const restoreFocusRef = useRef(false)

  // A term handed over by the sidebar is searched at once and then dropped from the history entry,
  // so neither a reload nor "back" brings it back.
  const { searchNow } = search
  useEffect(() => {
    const term = handedOverTerm(location.state)
    if (term === null) return
    searchNow(term)
    searchFieldRef.current?.focus()
    navigate(location.pathname, { replace: true, state: null })
  }, [location.key, location.state, location.pathname, navigate, searchNow])

  useEffect(() => {
    if (spaces.length === 0) void loadSpaces()
  }, [loadSpaces, spaces.length])

  useEffect(() => {
    if (chats === undefined) void loadChats(spaceId)
  }, [spaceId, chats, loadChats])

  // The archive's total feeds the tab label, so its first page loads with the page.
  useEffect(() => {
    void loadArchivedChats(spaceId, 0)
  }, [spaceId, loadArchivedChats])

  // Same order as the sidebar: pinned chats first, the rest by last use. The active chats are all
  // loaded anyway (the sidebar needs them), so they are paged here; the archive is paged server-side.
  const orderedActive = useMemo(() => {
    const { pinned, recent } = splitChats(chats ?? [])
    return [...pinned, ...recent]
  }, [chats])
  const activePageCount = Math.max(1, Math.ceil(orderedActive.length / CHAT_PAGE_SIZE))
  const shownActivePage = Math.min(activePage, activePageCount - 1)
  const rows: ChatSummary[] =
    tab === 'active'
      ? orderedActive.slice(
          shownActivePage * CHAT_PAGE_SIZE,
          (shownActivePage + 1) * CHAT_PAGE_SIZE,
        )
      : (archive?.items ?? [])
  // Only rows on the current page can be selected; anything else drops out of the selection.
  const selectedIds = rows.filter((chat) => selection.has(chat.id)).map((chat) => chat.id)
  const allSelected = rows.length > 0 && selectedIds.length === rows.length

  useEffect(() => {
    if (!restoreFocusRef.current || isBusy) return
    restoreFocusRef.current = false
    if (rows.length > 0) selectAllRef.current?.focus()
    else document.getElementById(`${idPrefix}-tab-${tab}`)?.focus()
  }, [isBusy, rows.length, tab, idPrefix])

  function changeTab(next: ChatsTab) {
    setTab(next)
    setSelection(EMPTY_SELECTION)
  }

  function changePage(page: number) {
    setSelection(EMPTY_SELECTION)
    if (tab === 'active') setActivePage(page)
    else void loadArchivedChats(spaceId, page)
  }

  function toggle(chatId: string) {
    setStatusMessage('')
    setSelection((current) => {
      const next = new Set(current)
      if (next.has(chatId)) next.delete(chatId)
      else next.add(chatId)
      return next
    })
  }

  function toggleAll() {
    setStatusMessage('')
    setSelection(allSelected ? EMPTY_SELECTION : new Set(rows.map((chat) => chat.id)))
  }

  /** Runs one action on the given chats - the bulk selection and the row menu share this path. */
  async function runAction(action: ChatBulkAction, targets: ChatSummary[]) {
    if (targets.length === 0) return
    if (action === 'DELETE') {
      const confirmed = await confirmAction({
        question:
          targets.length === 1
            ? `„${chatTitle(targets[0])}“ wirklich löschen?`
            : `${chatCount(targets.length)} wirklich löschen?`,
        consequence: 'Diese Aktion kann nicht rückgängig gemacht werden.',
        confirmLabel: 'Löschen',
        tone: 'danger',
      })
      if (!confirmed) return
    }
    setIsBusy(true)
    setStatusMessage('')
    const applied = await applyBulkAction(
      spaceId,
      action,
      targets.map((chat) => chat.id),
    )
    restoreFocusRef.current = true
    setIsBusy(false)
    if (applied === null) return
    setSelection((current) => {
      const next = new Set(current)
      for (const id of applied) next.delete(id)
      return next
    })
    setStatusMessage(`${chatCount(applied.length)} ${RESULT_VERBS[action]}`)
  }

  function pin(chat: ChatSummary, pinned: boolean) {
    setStatusMessage('')
    void setChatPinned(spaceId, chat.id, pinned).then((held) => {
      if (held) setStatusMessage(`„${chatTitle(chat)}“ ${pinned ? 'angeheftet' : 'gelöst'}`)
    })
  }

  function openChat(chatId: string) {
    navigate(`/spaces/${spaceId}/chats/${chatId}`)
  }

  const activeCount = chats?.length ?? 0
  const archivedCount = archive?.totalElements ?? 0
  const isTabLoading = tab === 'active' ? chats === undefined && isLoading : isLoadingArchive
  const tabPanelId = `${idPrefix}-panel`
  const tabId = (value: ChatsTab) => `${idPrefix}-tab-${value}`
  const selectedChats = rows.filter((chat) => selection.has(chat.id))
  const hasSelection = selectedIds.length > 0

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2, md: 3 }, overflowY: 'auto' }}>
      <Stack spacing={2}>
        <Box component="header" sx={{ borderBottom: 1, borderColor: 'divider', pb: 2 }}>
          <PageHeading title={space ? `Chats in „${space.name}“` : 'Chats'} />
          <Typography sx={{ color: 'text.secondary', mt: 0.5 }}>
            Ihre eigenen Chats in diesem Space. Archivierte Chats bleiben lesbar; wer darin
            weiterschreibt, holt sie aus dem Chat-Archiv zurück.
          </Typography>
        </Box>

        {error && <Alert severity="error">{error}</Alert>}

        <TextField
          type="search"
          size="small"
          fullWidth
          placeholder="Titel, Fragen und Antworten durchsuchen, auch im Chat-Archiv …"
          value={search.query}
          onChange={(event) => search.changeQuery(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter') {
              event.preventDefault()
              search.submit()
            } else if (event.key === 'Escape' && search.query !== '') {
              event.preventDefault()
              search.changeQuery('')
            }
          }}
          helperText={
            isSearching && !isSearchableTerm(search.query)
              ? `Bitte mindestens ${CHAT_SEARCH_MIN_LENGTH} Zeichen eingeben.`
              : undefined
          }
          slotProps={{
            input: {
              startAdornment: (
                <InputAdornment position="start">
                  <SearchIcon fontSize="small" aria-hidden />
                </InputAdornment>
              ),
            },
            htmlInput: {
              ref: searchFieldRef,
              maxLength: CHAT_SEARCH_MAX_LENGTH,
              'aria-label': 'In Chats suchen',
            },
          }}
        />

        {isSearching ? (
          <Box component="section" aria-label="Suchtreffer der Chatsuche">
            <ChatSearchResults
              spaceId={spaceId}
              search={search}
              onOpen={(path) => navigate(path)}
            />
          </Box>
        ) : (
          <Box>
            <Tabs
              value={tab}
              onChange={(_, value: ChatsTab) => changeTab(value)}
              aria-label="Chats nach Ablage"
              sx={{ borderBottom: 1, borderColor: 'divider' }}
            >
              <Tab
                value="active"
                id={tabId('active')}
                aria-controls={tabPanelId}
                label={`Aktiv (${activeCount})`}
              />
              <Tab
                value="archive"
                id={tabId('archive')}
                aria-controls={tabPanelId}
                label={`Archiv (${archivedCount})`}
              />
            </Tabs>

            <Box role="tabpanel" id={tabPanelId} aria-labelledby={tabId(tab)} sx={{ pt: 1.5 }}>
              {/* One slim bar above the list: "select all" on the left; on the right the result
                  of the last action, or - once something is selected - its count and the bulk
                  actions. Only buttons that can act are shown, none sits there disabled. */}
              <Stack
                direction="row"
                role="toolbar"
                aria-label="Sammelaktionen"
                sx={(theme) => ({
                  alignItems: 'center',
                  flexWrap: 'wrap',
                  columnGap: 1.5,
                  minHeight: 44,
                  px: { xs: 0.75, md: 2.25 },
                  mb: 1,
                  borderRadius: 1,
                  bgcolor: hasSelection
                    ? alpha(theme.palette.primary.main, theme.palette.mode === 'dark' ? 0.14 : 0.06)
                    : 'transparent',
                  transition: 'background-color 120ms',
                })}
              >
                <FormControlLabel
                  control={
                    <Checkbox
                      size="small"
                      slotProps={{ input: { ref: selectAllRef } }}
                      checked={allSelected}
                      indeterminate={!allSelected && hasSelection}
                      onChange={toggleAll}
                      disabled={rows.length === 0}
                    />
                  }
                  label="Alle auf dieser Seite auswählen"
                  slotProps={{ typography: { sx: { fontSize: 13 } } }}
                  sx={{ mr: 0 }}
                />
                <Box sx={{ flexGrow: 1 }} />
                {hasSelection ? (
                  <>
                    <Typography sx={{ fontSize: 13, fontWeight: 500 }}>
                      {selectedIds.length} ausgewählt
                    </Typography>
                    <Button
                      size="small"
                      disabled={isBusy}
                      onClick={() =>
                        void runAction(tab === 'active' ? 'ARCHIVE' : 'UNARCHIVE', selectedChats)
                      }
                    >
                      {tab === 'active' ? 'Archivieren' : 'Zurückholen'}
                    </Button>
                    <Button
                      size="small"
                      color="error"
                      disabled={isBusy}
                      onClick={() => void runAction('DELETE', selectedChats)}
                    >
                      Löschen
                    </Button>
                  </>
                ) : null}
                <Typography role="status" sx={{ fontSize: 12.5, color: 'text.secondary' }}>
                  {statusMessage}
                </Typography>
              </Stack>

              {isTabLoading ? (
                <ListLoading label="Chats werden geladen …" />
              ) : rows.length === 0 ? (
                tab === 'archive' ? (
                  <ListEmptyState
                    title="Das Chat-Archiv dieses Space ist leer."
                    hint="Archivierte Chats bleiben lesbar; wer darin weiterschreibt, holt sie zurück."
                  />
                ) : (
                  <ListEmptyState
                    title="Keine aktiven Chats in diesem Space."
                    hint="Einen neuen Chat beginnen Sie über „Neu“ in der Seitenleiste."
                  />
                )
              ) : (
                <ChatTable
                  spaceId={spaceId}
                  rows={rows}
                  archived={tab === 'archive'}
                  selection={selection}
                  onToggle={toggle}
                  onOpen={openChat}
                  busy={isBusy}
                  onAction={(action, targets) => void runAction(action, targets)}
                  onPin={pin}
                />
              )}

              <ListPager
                total={tab === 'active' ? orderedActive.length : archivedCount}
                size={CHAT_PAGE_SIZE}
                page={tab === 'active' ? shownActivePage : (archive?.page ?? 0)}
                onPage={changePage}
                countLabel={chatCount(tab === 'active' ? orderedActive.length : archivedCount)}
              />
            </Box>
          </Box>
        )}
      </Stack>
    </Box>
  )
}
