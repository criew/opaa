import { useEffect, useMemo, useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import Typography from '@mui/material/Typography'
import Box from '@mui/material/Box'
import CircularProgress from '@mui/material/CircularProgress'
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
  const [isUnarchiving, setIsUnarchiving] = useState(false)
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
          {/* aria-hidden: the visually hidden PageHeading above already announces the title. */}
          <Typography
            aria-hidden="true"
            component="div"
            noWrap
            sx={{ fontSize: 18, fontWeight: 600, flexGrow: 1, minWidth: 0 }}
          >
            {chatTitle}
          </Typography>
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
      <ChatInput onSend={(message) => sendMessage(message)} disabled={isLoading} />
    </Box>
  )
}
