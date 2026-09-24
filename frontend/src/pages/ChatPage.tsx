import { useEffect, useMemo, useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import ButtonBase from '@mui/material/ButtonBase'
import Chip from '@mui/material/Chip'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import Box from '@mui/material/Box'
import CircularProgress from '@mui/material/CircularProgress'
import EditOutlinedIcon from '@mui/icons-material/EditOutlined'
import { useNavigate, useParams, useSearchParams } from 'react-router'
import MessageList from '../components/chat/MessageList'
import ChatInput from '../components/chat/ChatInput'
import ConversationNote from '../components/chat/ConversationNote'
import { isConversationNoteVisible } from '../components/chat/conversationNoteVisibility'
import { useChatStore } from '../stores/chatStore'
import { useChatListStore } from '../stores/chatListStore'
import { notify } from '../stores/notificationStore'
import { useSpaceStore } from '../stores/spaceStore'
import PageHeading from '../components/a11y/PageHeading'

export default function ChatPage() {
  const { spaceId, chatId: routeChatId } = useParams<{ spaceId: string; chatId: string }>()
  const navigate = useNavigate()
  // `?message=` opens the chat at a chat search hit; it survives a reload like the chat id itself.
  const [searchParams] = useSearchParams()

  const messages = useChatStore((s) => s.messages)
  const isLoading = useChatStore((s) => s.isLoading)
  const isLoadingChat = useChatStore((s) => s.isLoadingChat)
  const error = useChatStore((s) => s.error)
  const sendMessage = useChatStore((s) => s.sendMessage)
  const loadChat = useChatStore((s) => s.loadChat)
  const startNewChat = useChatStore((s) => s.startNewChat)
  const storeSpaceId = useChatStore((s) => s.spaceId)
  const storeChatId = useChatStore((s) => s.chatId)
  const chatTitle = useChatStore((s) => s.title)
  const noteItems = useChatStore((s) => s.noteItems)
  const removeNoteItem = useChatStore((s) => s.removeNoteItem)
  const archivedAt = useChatStore((s) => s.archivedAt)
  const setChatArchived = useChatListStore((s) => s.setChatArchived)
  const renameChat = useChatListStore((s) => s.renameChat)
  const applyChatTitle = useChatStore((s) => s.applyTitle)
  const [isUnarchiving, setIsUnarchiving] = useState(false)
  // The open title field belongs to one chat: switching chats must never carry it - or its text -
  // along, so the chat it was opened for is part of the state.
  const [rename, setRename] = useState<{ chatId: string; value: string } | null>(null)
  const isRenaming = rename !== null && rename.chatId === storeChatId
  const renameValue = rename?.value ?? ''
  const setRenameValue = (value: string) =>
    setRename((current) => (current === null ? current : { ...current, value }))
  const closeRename = () => setRename(null)
  const titleButtonRef = useRef<HTMLButtonElement>(null)
  // The title button is unmounted while the field is open, so focus can only return to it on the
  // render that brings it back. A commit via blur leaves focus where the user put it.
  const refocusTitleRef = useRef(false)
  // #543: an archived space accepts no change to an existing chat - the points stay visible, the
  // remove buttons do not. Same lookup as ChatList's "Neuer Chat" guard.
  const isArchivedSpace = useSpaceStore(
    (s) => s.spaces.find((space) => space.id === spaceId)?.archived ?? false,
  )
  // A round is completed once its answer is in - the question of a round in flight does not count.
  const completedRounds = useMemo(
    () => messages.filter((message) => message.role === 'assistant').length,
    [messages],
  )
  const noteVisible = isConversationNoteVisible(noteItems, completedRounds)
  // Where focus goes when the last note point is removed and the note's surface disappears with it.
  const headerRef = useRef<HTMLDivElement>(null)

  // Read via a ref, not a reactive dependency: sendMessage sets the store's chatId as soon as it
  // implicitly creates a chat, well before the query itself resolves. If that update re-ran this
  // effect while the route still said "new", the isNewChat branch below would call startNewChat
  // again and wipe the just-sent, still in-flight user message right back out (#548 review,
  // finding 1) - the effect must only react to the route changing, and read the store's current
  // chatId (via the ref) purely to decide whether a refetch is necessary once it does. Synced in
  // its own effect (not during render) so it is up to date by the time the route actually changes,
  // without itself being a dependency that re-triggers the routing effect below.
  const storeChatIdRef = useRef(storeChatId)
  useEffect(() => {
    storeChatIdRef.current = storeChatId
  }, [storeChatId])

  const isNewChat = !routeChatId || routeChatId === 'new'
  const targetMessageId = isNewChat ? null : searchParams.get('message')

  // Loads the requested chat's history, or resets to a blank not-yet-persisted chat for the
  // current space - whichever the route asks for. Only re-runs when the route itself changes, not
  // on every store update.
  useEffect(() => {
    if (!spaceId) return
    if (!isNewChat && routeChatId) {
      // Already the active chat (e.g. just implicitly created by sendMessage, which replaces the
      // URL to point at it) - refetching here would load the not-yet-persisted history and
      // overwrite the message just shown (#548 review, finding 1).
      // A search hit names a server message id; questions of this session still carry client ids,
      // so a chat already open is reloaded when the hit is not among its messages.
      const targetMissing =
        targetMessageId !== null &&
        !useChatStore.getState().messages.some((message) => message.id === targetMessageId)
      if (routeChatId !== storeChatIdRef.current || targetMissing) {
        void loadChat(routeChatId)
      }
    } else {
      startNewChat(spaceId)
    }
  }, [spaceId, routeChatId, isNewChat, loadChat, startNewChat, targetMessageId])

  // The first message on a not-yet-persisted chat creates it implicitly (chatStore#sendMessage) -
  // once that happened, the URL is replaced to point at the real chat id so a reload restores it.
  //
  // previousStoreChatIdRef guards against a real bug found via CI (#548 follow-up): on the very
  // render where routeChatId just changed to "new", this effect's dependencies (isNewChat) changed
  // too, so it runs in the *same* effect flush as the routing effect above - but storeChatId here
  // still holds whatever chat was active *before* this navigation (the routing effect's
  // startNewChat() call only takes effect on a later render, since it goes through Zustand's own
  // subscription, not a synchronous update of this closure). Without the ref check below, this
  // effect would see a stale-but-truthy storeChatId, immediately navigate right back to that old
  // chat's URL, and defeat "start a new chat" entirely - the old chat's history (and any source
  // card in it) would still be exactly what the user sees. The ref makes this only fire on the
  // actual null -> id transition, i.e. once a chat has genuinely just been created in *this* new-
  // chat session, not merely "some chat id happens to be sitting in the store".
  const previousStoreChatIdRef = useRef(storeChatId)
  useEffect(() => {
    const previousStoreChatId = previousStoreChatIdRef.current
    previousStoreChatIdRef.current = storeChatId
    if (isNewChat && storeChatId && storeSpaceId && previousStoreChatId !== storeChatId) {
      navigate(`/spaces/${storeSpaceId}/chats/${storeChatId}`, { replace: true })
    }
  }, [isNewChat, storeChatId, storeSpaceId, navigate])

  // The message list is remounted per chat view: leaving a chat that waits for an answer ends the
  // view's loading state without an answer arriving, which the list must not announce as one. A
  // new chat receiving its id from its first question stays the same view.
  const [chatView, setChatView] = useState({ spaceId: storeSpaceId, chatId: storeChatId, key: 0 })
  if (chatView.spaceId !== storeSpaceId || chatView.chatId !== storeChatId) {
    const createdInView = chatView.spaceId === storeSpaceId && chatView.chatId === null
    setChatView({
      spaceId: storeSpaceId,
      chatId: storeChatId,
      key: createdInView ? chatView.key : chatView.key + 1,
    })
  }

  useEffect(() => {
    if (isRenaming || !refocusTitleRef.current) return
    refocusTitleRef.current = false
    titleButtonRef.current?.focus()
  }, [isRenaming])

  async function handleUnarchive() {
    if (!storeSpaceId || !storeChatId) return
    setIsUnarchiving(true)
    const done = await setChatArchived(storeSpaceId, storeChatId, false)
    setIsUnarchiving(false)
    if (done) {
      notify('Chat aus dem Archiv zurückgeholt', 'success')
      headerRef.current?.focus()
    } else {
      notify(
        useChatListStore.getState().error ?? 'Chat konnte nicht aus dem Archiv zurückgeholt werden',
        'error',
      )
    }
  }

  const isArchivedChat = !isNewChat && archivedAt !== null
  // #1919: renaming from the header writes through the same endpoint as the list's context menu,
  // so both surfaces and the list itself stay in step. An archived space accepts no change to a
  // chat, so there the title stays read-only.
  const canRename = Boolean(storeChatId) && !isArchivedSpace

  function startRename() {
    if (storeChatId) setRename({ chatId: storeChatId, value: chatTitle ?? '' })
  }

  async function commitRename(refocus: boolean) {
    const title = renameValue.trim()
    const chatId = storeChatId
    const previous = chatTitle
    if (refocus) refocusTitleRef.current = true
    closeRename()
    if (!chatId || !storeSpaceId || !title || title === previous) return
    // Shown at once and taken back if the server rejects it - the field is already gone by then.
    applyChatTitle(chatId, title)
    const renamed = await renameChat(storeSpaceId, chatId, title)
    if (!renamed) {
      applyChatTitle(chatId, previous)
      notify(useChatListStore.getState().error ?? 'Chat konnte nicht umbenannt werden', 'error')
    }
  }

  if (isLoadingChat) {
    return (
      <Box sx={{ flexGrow: 1, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <CircularProgress />
      </Box>
    )
  }

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', flexGrow: 1, minHeight: 0 }}>
      {/* Static heading text on purpose: the chat title falls back to the first question, and a
          hidden duplicate of a message would confuse text lookups (screen readers and E2E). */}
      <PageHeading
        title={isNewChat ? 'Neuer Chat' : 'Chat'}
        documentTitle={chatTitle ?? undefined}
        visuallyHidden
      />
      {(chatTitle || noteVisible || isArchivedChat) && (
        // Mockup 1a's header bar (#658), and the Gesprächsnotiz's place (#1488). Wrapping flex
        // row: title and button share the first line, the note panel takes the next one.
        <Box
          ref={headerRef}
          // Focus target of the note when its last point - and with it its button - is removed
          // (#1488). Named, because focus must never land on an element without name and role.
          tabIndex={-1}
          role="group"
          aria-label="Chat-Kopfzeile"
          sx={{
            px: 5,
            py: 2,
            borderBottom: 1,
            borderColor: 'divider',
            flexShrink: 0,
            display: 'flex',
            flexWrap: 'wrap',
            alignItems: 'center',
            gap: 2,
          }}
        >
          {chatTitle &&
            (isRenaming ? (
              <TextField
                // Opened deliberately by clicking the title or its pencil; moving focus into
                // the field is the expected inline-edit behaviour.
                // eslint-disable-next-line jsx-a11y-x/no-autofocus
                autoFocus
                size="small"
                value={renameValue}
                onChange={(event) => setRenameValue(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter') {
                    event.preventDefault()
                    void commitRename(true)
                  } else if (event.key === 'Escape') {
                    event.preventDefault()
                    refocusTitleRef.current = true
                    closeRename()
                  }
                }}
                onBlur={() => void commitRename(false)}
                slotProps={{ htmlInput: { 'aria-label': 'Chat-Titel' } }}
                sx={{ flexGrow: 1, minWidth: 0, maxWidth: 520 }}
              />
            ) : (
              // The title is a button, not a heading: the visually hidden PageHeading above
              // already announces the chat, so this row only has to be operable.
              <Box
                sx={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 0.5,
                  flexGrow: 1,
                  minWidth: 0,
                  '& .rename-hint': { opacity: 0 },
                  '&:hover .rename-hint, &:focus-within .rename-hint': { opacity: 1 },
                }}
              >
                <ButtonBase
                  ref={titleButtonRef}
                  onClick={startRename}
                  disabled={!canRename}
                  aria-label={`Chat-Titel „${chatTitle}“ umbenennen`}
                  sx={{
                    minWidth: 0,
                    borderRadius: '6px',
                    px: 0.5,
                    py: 0.25,
                    justifyContent: 'flex-start',
                  }}
                >
                  <Typography
                    component="span"
                    noWrap
                    sx={{ fontSize: 18, fontWeight: 600, minWidth: 0 }}
                  >
                    {chatTitle}
                  </Typography>
                </ButtonBase>
                {canRename && (
                  <EditOutlinedIcon
                    aria-hidden
                    className="rename-hint"
                    sx={{ fontSize: 15, color: 'text.secondary', transition: 'opacity 120ms' }}
                  />
                )}
              </Box>
            ))}
          {isArchivedChat && (
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
              {/* The chat archive of the person, not an archived space. */}
              <Chip size="small" variant="outlined" label="Archiviert" />
              <Button
                size="small"
                variant="outlined"
                onClick={() => void handleUnarchive()}
                disabled={isUnarchiving}
                aria-label="Chat aus dem Archiv zurückholen"
              >
                Zurückholen
              </Button>
            </Box>
          )}
          <ConversationNote
            // Expanding is per chat and per session: switching chats starts collapsed again.
            key={storeChatId ?? 'new'}
            items={noteItems}
            completedRounds={completedRounds}
            canRemove={!isArchivedSpace}
            onRemove={(itemId) => void removeNoteItem(itemId)}
            emptyFocusRef={headerRef}
          />
        </Box>
      )}
      {error && (
        <Alert severity="error" sx={{ m: 2 }}>
          {error}
        </Alert>
      )}
      <MessageList
        key={chatView.key}
        messages={messages}
        isLoading={isLoading}
        targetMessageId={targetMessageId}
      />
      <ChatInput
        onSend={(message, usedPrompt) => sendMessage(message, usedPrompt)}
        disabled={isLoading}
      />
    </Box>
  )
}
