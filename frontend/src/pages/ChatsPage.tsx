import { useEffect, useId, useMemo, useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import CircularProgress from '@mui/material/CircularProgress'
import FormControlLabel from '@mui/material/FormControlLabel'
import Link from '@mui/material/Link'
import Stack from '@mui/material/Stack'
import Tab from '@mui/material/Tab'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TablePagination from '@mui/material/TablePagination'
import TableRow from '@mui/material/TableRow'
import Tabs from '@mui/material/Tabs'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import visuallyHidden from '@mui/utils/visuallyHidden'
import { useLocation, useNavigate, useParams } from 'react-router'
import PageHeading from '../components/a11y/PageHeading'
import ChatSearchResults from '../components/chat/ChatSearchResults'
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

const dateTimeFormat = new Intl.DateTimeFormat('de-DE', {
  dateStyle: 'medium',
  timeStyle: 'short',
})

function formatDateTime(iso: string | null | undefined): string {
  return iso ? dateTimeFormat.format(new Date(iso)) : ''
}

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
 * "Die Seite ‚Chats‘ je Space"): the active chats and the person's chat archive as tabs, with
 * multi-selection and bulk actions. A click on a title opens the chat. While a term is entered,
 * the chat search's hits take the place of the tabs.
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
  // The action buttons are disabled while a bulk action runs and once the selection is gone, which
  // drops their focus; it is placed back into the toolbar (or onto the tab) once the list settled.
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

  // Same order as the sidebar: pinned chats first, the rest by last use.
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

  function toggle(chatId: string) {
    setSelection((current) => {
      const next = new Set(current)
      if (next.has(chatId)) next.delete(chatId)
      else next.add(chatId)
      return next
    })
  }

  function toggleAll() {
    setSelection(allSelected ? EMPTY_SELECTION : new Set(rows.map((chat) => chat.id)))
  }

  async function runBulkAction(action: ChatBulkAction) {
    const ids = selectedIds
    if (ids.length === 0) return
    if (action === 'DELETE') {
      const confirmed = await confirmAction({
        question: `${chatCount(ids.length)} wirklich löschen?`,
        consequence: 'Diese Aktion kann nicht rückgängig gemacht werden.',
        confirmLabel: 'Löschen',
        tone: 'danger',
      })
      if (!confirmed) return
    }
    setIsBusy(true)
    setStatusMessage('')
    const applied = await applyBulkAction(spaceId, action, ids)
    restoreFocusRef.current = true
    setIsBusy(false)
    if (applied === null) return
    setSelection(EMPTY_SELECTION)
    setStatusMessage(`${chatCount(applied.length)} ${RESULT_VERBS[action]}`)
  }

  function openChat(chatId: string) {
    navigate(`/spaces/${spaceId}/chats/${chatId}`)
  }

  const activeCount = chats?.length ?? 0
  const archivedCount = archive?.totalElements ?? 0
  const isTabLoading = tab === 'active' ? chats === undefined && isLoading : isLoadingArchive
  const tabPanelId = `${idPrefix}-panel`
  const tabId = (value: ChatsTab) => `${idPrefix}-tab-${value}`

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
          label="In Chats suchen"
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
              : 'Titel, Fragen und Antworten Ihrer Chats in diesem Space, auch im Chat-Archiv'
          }
          slotProps={{
            htmlInput: { ref: searchFieldRef, maxLength: CHAT_SEARCH_MAX_LENGTH },
          }}
          sx={{ width: { xs: '100%', sm: 420 } }}
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
          <>
            <Tabs
              value={tab}
              onChange={(_, value: ChatsTab) => changeTab(value)}
              aria-label="Chats nach Ablage"
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

            <Box role="tabpanel" id={tabPanelId} aria-labelledby={tabId(tab)}>
              <Stack
                direction="row"
                spacing={1.5}
                role="toolbar"
                aria-label="Sammelaktionen"
                sx={{ alignItems: 'center', flexWrap: 'wrap', mb: 1 }}
              >
                <FormControlLabel
                  control={
                    <Checkbox
                      size="small"
                      slotProps={{ input: { ref: selectAllRef } }}
                      checked={allSelected}
                      indeterminate={!allSelected && selectedIds.length > 0}
                      onChange={toggleAll}
                      disabled={rows.length === 0}
                    />
                  }
                  label="Alle auf dieser Seite auswählen"
                />
                <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                  {selectedIds.length} ausgewählt
                </Typography>
                {tab === 'active' ? (
                  <Button
                    size="small"
                    variant="outlined"
                    disabled={selectedIds.length === 0 || isBusy}
                    onClick={() => void runBulkAction('ARCHIVE')}
                  >
                    Archivieren
                  </Button>
                ) : (
                  <Button
                    size="small"
                    variant="outlined"
                    disabled={selectedIds.length === 0 || isBusy}
                    onClick={() => void runBulkAction('UNARCHIVE')}
                  >
                    Zurückholen
                  </Button>
                )}
                <Button
                  size="small"
                  variant="outlined"
                  color="error"
                  disabled={selectedIds.length === 0 || isBusy}
                  onClick={() => void runBulkAction('DELETE')}
                >
                  Löschen
                </Button>
              </Stack>

              <Typography role="status" variant="body2" sx={{ minHeight: 20, mb: 1 }}>
                {statusMessage}
              </Typography>

              {isTabLoading ? (
                <Box sx={{ py: 3, display: 'flex', justifyContent: 'center' }}>
                  <CircularProgress size={24} aria-label="Chats werden geladen" />
                </Box>
              ) : rows.length === 0 ? (
                <Typography sx={{ color: 'text.secondary' }}>
                  {tab === 'archive'
                    ? 'Das Chat-Archiv dieses Space ist leer.'
                    : 'Keine aktiven Chats in diesem Space.'}
                </Typography>
              ) : (
                <Table size="small" aria-label={tab === 'active' ? 'Aktive Chats' : 'Chat-Archiv'}>
                  <TableHead>
                    <TableRow>
                      <TableCell padding="checkbox">
                        <Box component="span" sx={visuallyHidden}>
                          Auswahl
                        </Box>
                      </TableCell>
                      <TableCell>Titel</TableCell>
                      <TableCell>{tab === 'active' ? 'Angeheftet' : 'Archiviert am'}</TableCell>
                      <TableCell>Letzte Aktivität</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {rows.map((chat) => {
                      const title = chatTitle(chat)
                      return (
                        <TableRow key={chat.id} hover selected={selection.has(chat.id)}>
                          <TableCell padding="checkbox">
                            <Checkbox
                              size="small"
                              checked={selection.has(chat.id)}
                              onChange={() => toggle(chat.id)}
                              slotProps={{ input: { 'aria-label': `„${title}“ auswählen` } }}
                            />
                          </TableCell>
                          <TableCell>
                            <Link
                              href={`/spaces/${spaceId}/chats/${chat.id}`}
                              onClick={(event) => {
                                event.preventDefault()
                                openChat(chat.id)
                              }}
                              underline="hover"
                            >
                              {title}
                            </Link>
                          </TableCell>
                          <TableCell>
                            {tab === 'active'
                              ? chat.pinnedAt
                                ? 'angeheftet'
                                : ''
                              : formatDateTime(chat.archivedAt)}
                          </TableCell>
                          <TableCell>{formatDateTime(chat.updatedAt)}</TableCell>
                        </TableRow>
                      )
                    })}
                  </TableBody>
                </Table>
              )}

              {tab === 'active' && orderedActive.length > CHAT_PAGE_SIZE && (
                <TablePagination
                  component="div"
                  count={orderedActive.length}
                  page={shownActivePage}
                  rowsPerPage={CHAT_PAGE_SIZE}
                  rowsPerPageOptions={[]}
                  onPageChange={(_, page) => setActivePage(page)}
                  labelDisplayedRows={({ from, to, count }) => `${from}–${to} von ${count}`}
                  getItemAriaLabel={(type) =>
                    type === 'next'
                      ? 'Nächste Seite'
                      : type === 'previous'
                        ? 'Vorherige Seite'
                        : type
                  }
                />
              )}
              {tab === 'archive' && archivedCount > CHAT_PAGE_SIZE && (
                <TablePagination
                  component="div"
                  count={archivedCount}
                  page={archive?.page ?? 0}
                  rowsPerPage={CHAT_PAGE_SIZE}
                  rowsPerPageOptions={[]}
                  onPageChange={(_, page) => {
                    setSelection(EMPTY_SELECTION)
                    void loadArchivedChats(spaceId, page)
                  }}
                  labelDisplayedRows={({ from, to, count }) => `${from}–${to} von ${count}`}
                  getItemAriaLabel={(type) =>
                    type === 'next'
                      ? 'Nächste Seite'
                      : type === 'previous'
                        ? 'Vorherige Seite'
                        : type
                  }
                />
              )}
            </Box>
          </>
        )}
      </Stack>
    </Box>
  )
}
