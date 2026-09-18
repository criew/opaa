import { useEffect, useId, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import ButtonBase from '@mui/material/ButtonBase'
import CircularProgress from '@mui/material/CircularProgress'
import IconButton from '@mui/material/IconButton'
import Divider from '@mui/material/Divider'
import ListItemIcon from '@mui/material/ListItemIcon'
import Menu from '@mui/material/Menu'
import MenuItem from '@mui/material/MenuItem'
import List from '@mui/material/List'
import ListItem from '@mui/material/ListItem'
import ListItemButton from '@mui/material/ListItemButton'
import ListItemText from '@mui/material/ListItemText'
import TextField from '@mui/material/TextField'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import AddIcon from '@mui/icons-material/Add'
import DeleteIcon from '@mui/icons-material/Delete'
import EditIcon from '@mui/icons-material/Edit'
import ExpandLessIcon from '@mui/icons-material/ExpandLess'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import MoreVertIcon from '@mui/icons-material/MoreVert'
import PushPinIcon from '@mui/icons-material/PushPin'
import PushPinOutlinedIcon from '@mui/icons-material/PushPinOutlined'
import visuallyHidden from '@mui/utils/visuallyHidden'
import { ThemeProvider } from '@mui/material/styles'
import type { Theme } from '@mui/material/styles'
import { useLocation, useNavigate } from 'react-router'
import { blue } from '../../theme/tokens'
import type { ChatSummary } from '../../types/api'
import { useChatListStore } from '../../stores/chatListStore'
import { confirmAction } from '../../stores/confirmStore'
import { useSpaceStore } from '../../stores/spaceStore'
import { chatTitle, groupChats, matchesTitle } from './chatListGroups'

function filterStatusText(query: string, matches: number): string {
  if (query.trim() === '') return ''
  if (matches === 0) return 'Kein Chat mit diesem Titel'
  return matches === 1 ? '1 Chat gefunden' : `${matches} Chats gefunden`
}

interface ChatListProps {
  spaceId: string
  /** Rendered left of the "+ Neu" action, in the same row (mockup 1a's section head). */
  header?: ReactNode
  /**
   * Theme for the context menu. Mockup 1a shows light panels even over the navy sidebar, so
   * the Sidebar passes the app theme in here - the list itself stays on the sidebar theme.
   */
  menuTheme?: Theme
}

/**
 * A person's chats in one space (docs/features/chat-list.md, "Die Seitenleiste"): a title filter
 * over the loaded list, pinned chats first, the rest in time groups by last activity.
 */
export default function ChatList({ spaceId, header, menuTheme }: ChatListProps) {
  const navigate = useNavigate()
  const location = useLocation()
  const chats = useChatListStore((s) => s.chatsBySpaceId[spaceId])
  const isLoading = useChatListStore((s) => s.isLoading)
  const error = useChatListStore((s) => s.error)
  const loadChats = useChatListStore((s) => s.loadChats)
  const renameChat = useChatListStore((s) => s.renameChat)
  const deleteChatFromList = useChatListStore((s) => s.deleteChatFromList)
  const setChatPinned = useChatListStore((s) => s.setChatPinned)
  // #543/#613 review, nit c: an archived space accepts no new chats - the "Neuer Chat" button is
  // disabled rather than hidden, so it stays a stable click target and the reason is explained via
  // its tooltip instead of the button silently vanishing.
  const isArchived = useSpaceStore((s) => s.spaces.find((space) => space.id === spaceId)?.archived)

  const [renamingChatId, setRenamingChatId] = useState<string | null>(null)
  const [renameValue, setRenameValue] = useState('')
  const [menuAnchor, setMenuAnchor] = useState<{ chatId: string; el: HTMLElement } | null>(null)
  const [titleFilter, setTitleFilter] = useState('')
  // View state only: neither persisted nor sent to the server.
  const [pinnedCollapsed, setPinnedCollapsed] = useState(false)
  const groupIdPrefix = useId()
  // Pinning moves a row into another group, which remounts it; focus follows it there.
  const refocusMovedChatIdRef = useRef<string | null>(null)
  // A row's actions button is unmounted while the row is in rename mode, so focus can only
  // return to it after the re-render that brings it back - hence the pending id is stashed in
  // a ref and consumed once rename mode ends. Blur commits deliberately don't refocus: the
  // user moved focus elsewhere.
  const refocusChatIdRef = useRef<string | null>(null)
  const actionButtonRefs = useRef<Map<string, HTMLButtonElement> | null>(null)

  function getActionButtonRefs() {
    actionButtonRefs.current ??= new Map()
    return actionButtonRefs.current
  }

  useEffect(() => {
    if (renamingChatId !== null) return
    const chatId = refocusChatIdRef.current
    if (chatId === null) return
    refocusChatIdRef.current = null
    actionButtonRefs.current?.get(chatId)?.focus()
  }, [renamingChatId])

  useEffect(() => {
    const chatId = refocusMovedChatIdRef.current
    if (chatId === null) return
    refocusMovedChatIdRef.current = null
    actionButtonRefs.current?.get(chatId)?.focus()
  }, [chats])

  useEffect(() => {
    if (chats === undefined) {
      void loadChats(spaceId)
    }
  }, [spaceId, chats, loadChats])

  // Routes through the not-yet-persisted "new" chat state instead of eagerly creating a chat here
  // - the first sent message is the only place a chat gets created (chatStore#sendMessage), so a
  // chat that's never actually used never gets persisted either (#548 review, nit a).
  function handleNewChat() {
    navigate(`/spaces/${spaceId}/chats/new`)
  }

  function startRename(chat: ChatSummary) {
    setRenamingChatId(chat.id)
    setRenameValue(chat.title ?? '')
  }

  async function commitRename(chatId: string) {
    const title = renameValue.trim()
    setRenamingChatId(null)
    if (!title) return
    await renameChat(spaceId, chatId, title)
  }

  async function handleDelete(chat: ChatSummary) {
    const confirmed = await confirmAction({
      question: `„${chatTitle(chat)}“ wirklich löschen?`,
      consequence: 'Diese Aktion kann nicht rückgängig gemacht werden.',
      confirmLabel: 'Löschen',
      tone: 'danger',
    })
    if (!confirmed) return
    await deleteChatFromList(spaceId, chat.id)
    if (location.pathname === `/spaces/${spaceId}/chats/${chat.id}`) {
      navigate(`/spaces/${spaceId}/chats/new`, { replace: true })
    }
  }

  function renderChatRow(chat: ChatSummary) {
    const active = location.pathname === `/spaces/${spaceId}/chats/${chat.id}`
    const isRenaming = renamingChatId === chat.id
    return (
      <ListItem
        key={chat.id}
        disablePadding
        // Mockup 1a keeps chat rows quiet - the actions only surface on hover or
        // keyboard focus (#658). They stay in the tab order either way.
        sx={{
          '& .MuiListItemSecondaryAction-root': {
            opacity: menuAnchor?.chatId === chat.id ? 1 : 0,
            transition: 'opacity 120ms',
          },
          '&:hover .MuiListItemSecondaryAction-root, &:focus-within .MuiListItemSecondaryAction-root':
            { opacity: 1 },
        }}
        secondaryAction={
          !isRenaming && (
            // Mockup 1a: one quiet three-dot trigger per row, the actions live in a
            // light dropdown (#658 Nachbesserung).
            <IconButton
              size="small"
              ref={(el) => {
                if (el) getActionButtonRefs().set(chat.id, el)
                else getActionButtonRefs().delete(chat.id)
              }}
              aria-label={`Aktionen für Chat „${chatTitle(chat)}“`}
              aria-haspopup="menu"
              aria-expanded={menuAnchor?.chatId === chat.id ? 'true' : undefined}
              onClick={(event) => setMenuAnchor({ chatId: chat.id, el: event.currentTarget })}
              sx={{ p: 0.5, borderRadius: '4px' }}
            >
              <MoreVertIcon sx={{ fontSize: 16 }} />
            </IconButton>
          )
        }
      >
        <ListItemButton
          selected={active}
          onClick={isRenaming ? undefined : () => navigate(`/spaces/${spaceId}/chats/${chat.id}`)}
          sx={{ borderRadius: '6px', mb: 0.25, pr: 5.5, py: 0.5 }}
        >
          {isRenaming ? (
            <TextField
              // The field appears only after the user chose "Umbenennen"; moving focus into it is
              // the expected inline-edit behaviour. Re-verified in the closing audit, see #598.
              // eslint-disable-next-line jsx-a11y-x/no-autofocus
              autoFocus
              size="small"
              fullWidth
              value={renameValue}
              slotProps={{ htmlInput: { 'aria-label': 'Chat-Titel' } }}
              onClick={(e) => e.stopPropagation()}
              onChange={(e) => setRenameValue(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  e.preventDefault()
                  refocusChatIdRef.current = chat.id
                  void commitRename(chat.id)
                } else if (e.key === 'Escape') {
                  e.preventDefault()
                  refocusChatIdRef.current = chat.id
                  setRenamingChatId(null)
                }
              }}
              onBlur={() => void commitRename(chat.id)}
            />
          ) : (
            <>
              {chat.pinnedAt && (
                // Decorative: the "Angeheftet" group heading states the pin in text.
                <PushPinIcon aria-hidden sx={{ fontSize: 12, mr: 0.75, opacity: 0.7 }} />
              )}
              <ListItemText
                primary={chatTitle(chat)}
                slotProps={{ primary: { noWrap: true, variant: 'body2' } }}
              />
            </>
          )}
        </ListItemButton>
      </ListItem>
    )
  }

  function renderChats(allChats: ChatSummary[]) {
    const matching = allChats.filter((chat) => matchesTitle(chat, titleFilter))
    const groups = groupChats(matching, new Date())
    return (
      <>
        <TextField
          type="search"
          size="small"
          fullWidth
          placeholder="Chats filtern …"
          value={titleFilter}
          onChange={(event) => setTitleFilter(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Escape' && titleFilter !== '') {
              event.preventDefault()
              event.stopPropagation()
              setTitleFilter('')
            }
          }}
          slotProps={{ htmlInput: { 'aria-label': 'Chats filtern' } }}
          sx={{ mb: 0.5, '& .MuiInputBase-input': { py: 0.75, fontSize: 12.5 } }}
        />
        <Box role="status" sx={visuallyHidden}>
          {filterStatusText(titleFilter, matching.length)}
        </Box>
        {matching.length === 0 && (
          <Typography sx={{ color: 'text.secondary', mt: 1 }} variant="body2">
            Kein Chat mit diesem Titel
          </Typography>
        )}
        {groups.map((group) => {
          const headingId = `${groupIdPrefix}-${group.key}`
          const listId = `${headingId}-list`
          const isPinnedGroup = group.key === 'pinned'
          const collapsed = isPinnedGroup && pinnedCollapsed
          return (
            <Box key={group.key} sx={{ mt: 0.75 }}>
              <Typography
                component="h3"
                variant="overline"
                id={headingId}
                sx={{ display: 'block', color: 'text.secondary', lineHeight: 1.8, px: 1 }}
              >
                {isPinnedGroup ? (
                  <ButtonBase
                    aria-expanded={!collapsed}
                    aria-controls={listId}
                    onClick={() => setPinnedCollapsed((value) => !value)}
                    sx={{
                      font: 'inherit',
                      color: 'inherit',
                      letterSpacing: 'inherit',
                      textTransform: 'inherit',
                      gap: 0.5,
                      borderRadius: '4px',
                    }}
                  >
                    {group.label}
                    {collapsed ? (
                      <ExpandMoreIcon aria-hidden sx={{ fontSize: 14 }} />
                    ) : (
                      <ExpandLessIcon aria-hidden sx={{ fontSize: 14 }} />
                    )}
                  </ButtonBase>
                ) : (
                  group.label
                )}
              </Typography>
              {!collapsed && (
                <List id={listId} aria-labelledby={headingId} sx={{ px: 0, py: 0 }}>
                  {group.chats.map((chat) => renderChatRow(chat))}
                </List>
              )}
            </Box>
          )
        })}
      </>
    )
  }

  return (
    <Box>
      {/* Mockup 1a: section head and the "+ Neu" action share one baseline row (#658). */}
      <Box
        sx={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', pb: 0.5 }}
      >
        {header ?? <span />}
        <Tooltip
          title={
            isArchived ? 'Dieser Space ist archiviert und nimmt keine neuen Chats mehr an' : ''
          }
        >
          <span>
            <Button
              variant="text"
              size="small"
              aria-label="Neuer Chat"
              startIcon={<AddIcon sx={{ fontSize: 13 }} />}
              onClick={handleNewChat}
              disabled={Boolean(isArchived)}
              // Mockup 1a: a quiet small link, not a boxed button. Blue-300 on the navy/carbon
              // sidebar; on light surfaces (SpacePage renders this list on white) blue-700 keeps
              // the 4.5:1 contrast the a11y suite enforces (#586).
              sx={{
                minHeight: 0,
                px: 0.75,
                py: 0.25,
                fontSize: 11.5,
                color: (theme) => (theme.palette.mode === 'dark' ? blue[300] : blue[700]),
              }}
            >
              Neu
            </Button>
          </span>
        </Tooltip>
      </Box>

      {error && (
        <Typography variant="body2" sx={{ color: 'error.main', mb: 1 }}>
          {error}
        </Typography>
      )}

      {chats === undefined && isLoading ? (
        <Box sx={{ py: 2, display: 'flex', justifyContent: 'center' }}>
          <CircularProgress size={20} />
        </Box>
      ) : !chats || chats.length === 0 ? (
        <Typography sx={{ color: 'text.secondary' }} variant="body2">
          Noch keine Chats in diesem Space.
        </Typography>
      ) : (
        renderChats(chats)
      )}

      {(() => {
        const menuChat = chats?.find((chat) => chat.id === menuAnchor?.chatId)
        const pinned = Boolean(menuChat?.pinnedAt)
        const menu = (
          <Menu
            anchorEl={menuAnchor?.el ?? null}
            open={Boolean(menuAnchor && menuChat)}
            onClose={() => setMenuAnchor(null)}
            slotProps={{ paper: { sx: { width: 190 } }, list: { 'aria-label': 'Chat-Aktionen' } }}
          >
            <MenuItem
              aria-label={menuChat ? `Chat „${chatTitle(menuChat)}“ umbenennen` : undefined}
              onClick={() => {
                setMenuAnchor(null)
                if (menuChat) startRename(menuChat)
              }}
            >
              <ListItemIcon>
                <EditIcon sx={{ fontSize: 15 }} />
              </ListItemIcon>
              Umbenennen
            </MenuItem>
            <MenuItem
              aria-label={
                menuChat
                  ? `Chat „${chatTitle(menuChat)}“ ${pinned ? 'lösen' : 'anheften'}`
                  : undefined
              }
              onClick={() => {
                setMenuAnchor(null)
                if (!menuChat) return
                refocusMovedChatIdRef.current = menuChat.id
                void setChatPinned(spaceId, menuChat.id, !pinned)
              }}
            >
              <ListItemIcon>
                {pinned ? (
                  <PushPinOutlinedIcon sx={{ fontSize: 15 }} />
                ) : (
                  <PushPinIcon sx={{ fontSize: 15 }} />
                )}
              </ListItemIcon>
              {pinned ? 'Lösen' : 'Anheften'}
            </MenuItem>
            <Divider sx={{ my: 0.5 }} />
            <MenuItem
              aria-label={menuChat ? `Chat „${chatTitle(menuChat)}“ löschen` : undefined}
              onClick={() => {
                setMenuAnchor(null)
                if (menuChat) void handleDelete(menuChat)
              }}
              sx={{ color: 'error.main' }}
            >
              <ListItemIcon>
                <DeleteIcon sx={{ fontSize: 15, color: 'error.main' }} />
              </ListItemIcon>
              Löschen
            </MenuItem>
          </Menu>
        )
        return menuTheme ? <ThemeProvider theme={menuTheme}>{menu}</ThemeProvider> : menu
      })()}
    </Box>
  )
}
