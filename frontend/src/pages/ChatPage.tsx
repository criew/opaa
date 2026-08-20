import { useEffect, useRef } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import CircularProgress from '@mui/material/CircularProgress'
import { useNavigate, useParams } from 'react-router'
import MessageList from '../components/chat/MessageList'
import ChatInput from '../components/chat/ChatInput'
import { useChatStore } from '../stores/chatStore'
import PageHeading from '../components/a11y/PageHeading'

export default function ChatPage() {
  const { spaceId, chatId: routeChatId } = useParams<{ spaceId: string; chatId: string }>()
  const navigate = useNavigate()

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

  // Loads the requested chat's history, or resets to a blank not-yet-persisted chat for the
  // current space - whichever the route asks for. Only re-runs when the route itself changes, not
  // on every store update.
  useEffect(() => {
    if (!spaceId) return
    if (!isNewChat && routeChatId) {
      // Already the active chat (e.g. just implicitly created by sendMessage, which replaces the
      // URL to point at it) - refetching here would load the not-yet-persisted history and
      // overwrite the message just shown (#548 review, finding 1).
      if (routeChatId !== storeChatIdRef.current) {
        void loadChat(routeChatId)
      }
    } else {
      startNewChat(spaceId)
    }
  }, [spaceId, routeChatId, isNewChat, loadChat, startNewChat])

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
      {error && (
        <Alert severity="error" sx={{ m: 2 }}>
          {error}
        </Alert>
      )}
      <MessageList messages={messages} isLoading={isLoading} />
      <ChatInput onSend={(message) => sendMessage(message)} disabled={isLoading} />
    </Box>
  )
}
