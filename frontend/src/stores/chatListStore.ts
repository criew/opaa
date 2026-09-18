import { create } from 'zustand'
import type { ChatBulkAction, ChatSummary } from '../types/api'
import {
  applyChatBulkAction,
  archiveChat,
  createChat,
  deleteChat,
  listArchivedSpaceChats,
  listSpaceChats,
  pinChat,
  unarchiveChat,
  unpinChat,
  updateChat,
} from '../services/api'
import { dropChatSettingsCache, useChatStore } from './chatStore'
import { currentSessionEpoch, isStaleSessionEpoch } from './sessionEpoch'

/** Page size of the chat archive and of the active table on the "Chats" page. */
export const CHAT_PAGE_SIZE = 50

/** The loaded page of a space's chat archive. */
export interface ChatArchivePage {
  items: ChatSummary[]
  page: number
  totalElements: number
}

interface ChatListState {
  /** Chats per space, sorted by last use (most recently updated first). Undefined means "not
   * loaded yet" for that space - distinct from an empty array, which means "loaded, no chats". */
  chatsBySpaceId: Record<string, ChatSummary[] | undefined>
  isLoading: boolean
  error: string | null
  loadChats: (spaceId: string) => Promise<void>
  /** Returns null (and sets `error`) when creation fails, instead of throwing - callers must
   * handle the null case explicitly rather than relying on a rejected promise. */
  createChatInSpace: (spaceId: string) => Promise<ChatSummary | null>
  renameChat: (spaceId: string, chatId: string, title: string) => Promise<void>
  deleteChatFromList: (spaceId: string, chatId: string) => Promise<void>
  /** Pins or unpins a chat for the current person. Applied optimistically and rolled back (with
   * `error` set) if the server rejects it. Requests for one chat reach the server in the order
   * they were made, and only the latest one decides what the list shows. Resolves to false if
   * this request's change did not hold. */
  setChatPinned: (spaceId: string, chatId: string, pinned: boolean) => Promise<boolean>
  /** The current page of each space's chat archive; undefined until the archive is first loaded. */
  archiveBySpaceId: Record<string, ChatArchivePage | undefined>
  isLoadingArchive: boolean
  /** Loads one page of the space's chat archive (default: the page already shown, else the
   * first); a page that has emptied out falls back to the last page that still has entries. */
  loadArchivedChats: (spaceId: string, page?: number) => Promise<void>
  /** Moves a chat into the person's chat archive or back. Resolves to false (with `error` set) if
   * the server rejects it. */
  setChatArchived: (spaceId: string, chatId: string, archived: boolean) => Promise<boolean>
  /** Applies a bulk action to chats of the space; resolves to the ids the server applied it to,
   * or null (with `error` set) on failure. */
  applyBulkAction: (
    spaceId: string,
    action: ChatBulkAction,
    chatIds: string[],
  ) => Promise<string[] | null>
  /** Refreshes the active list and the archive after the person's own message brought a chat back
   * from the archive. */
  chatReturnedFromArchive: (spaceId: string) => Promise<void>
  /** Inserts a chat into its space's list (or replaces an existing entry with the same id) and
   * re-sorts by last use - used by chatStore to make an implicitly created chat show up in the
   * list without a full reload (#548 review, finding 4). */
  upsertChat: (spaceId: string, chat: ChatSummary) => void
  /** Bumps an existing entry's updatedAt and re-sorts, so a chat moves to the top of its list
   * after every turn - a no-op if the chat isn't in the (possibly not yet loaded) list. */
  touchChat: (spaceId: string, chatId: string, updatedAt: string) => void
  /** Applies a title change - either the immediate fallback QueryResponse#chatTitle carries, or
   * the LLM-derived title chatStore's delayed reload picks up (#557) - to a chat already in the
   * list. A no-op if the chat isn't in the (possibly not yet loaded) list. */
  updateChatTitle: (spaceId: string, chatId: string, title: string | null) => void
  reset: () => void
}

// Compared as instants, not strings: the server omits trailing zero fractions, so ISO strings of
// the same second do not sort lexicographically.
function sortByLastUse(chats: ChatSummary[]): ChatSummary[] {
  return [...chats].sort((a, b) => Date.parse(b.updatedAt) - Date.parse(a.updatedAt))
}

function withPinnedAt(
  chats: ChatSummary[] | undefined,
  chatId: string,
  pinnedAt: string | null,
): ChatSummary[] | undefined {
  return chats?.map((chat) => (chat.id === chatId ? { ...chat, pinnedAt } : chat))
}

// Pin requests per chat run one after another, so the server sees them in the order they were
// made; only the latest request of a chat applies its outcome to the list.
const pinRequestChains = new Map<string, Promise<unknown>>()
const latestPinRequest = new Map<string, number>()
// The server's last confirmed pin state per chat - where a failed latest request rolls back to.
const confirmedPinnedAt = new Map<string, string | null>()
let pinRequestCounter = 0

function withoutChat(chats: ChatSummary[] | undefined, chatId: string) {
  return chats?.filter((chat) => chat.id !== chatId)
}

function toChatSummary(detail: {
  id: string
  spaceId: string
  authorId: string
  title?: string | null
  useKnowledge: boolean
  referencedLibraryIds?: string[]
  status: ChatSummary['status']
  createdAt: string
  updatedAt: string
}): ChatSummary {
  return {
    id: detail.id,
    spaceId: detail.spaceId,
    authorId: detail.authorId,
    title: detail.title ?? null,
    useKnowledge: detail.useKnowledge,
    referencedLibraryIds: detail.referencedLibraryIds ?? [],
    status: detail.status,
    createdAt: detail.createdAt,
    updatedAt: detail.updatedAt,
  }
}

export const useChatListStore = create<ChatListState>((set, get) => ({
  chatsBySpaceId: {},
  isLoading: false,
  error: null,
  archiveBySpaceId: {},
  isLoadingArchive: false,

  reset: () =>
    set({
      chatsBySpaceId: {},
      isLoading: false,
      error: null,
      archiveBySpaceId: {},
      isLoadingArchive: false,
    }),

  loadChats: async (spaceId: string) => {
    // #575: captured before the await below - checked again once it resolves, so a response
    // arriving after a logout (resetAllStores) skips its write-back instead of resurrecting the
    // previous user's chat list into the now-emptied store.
    const sessionEpoch = currentSessionEpoch()
    set({ isLoading: true, error: null })
    try {
      const chats = sortByLastUse(await listSpaceChats(spaceId))
      if (isStaleSessionEpoch(sessionEpoch)) return
      set((state) => ({
        chatsBySpaceId: { ...state.chatsBySpaceId, [spaceId]: chats },
        isLoading: false,
      }))
    } catch (err) {
      if (isStaleSessionEpoch(sessionEpoch)) return
      const message = err instanceof Error ? err.message : 'Chats konnten nicht geladen werden'
      set({ error: message, isLoading: false })
    }
  },

  createChatInSpace: async (spaceId: string) => {
    const sessionEpoch = currentSessionEpoch()
    try {
      const detail = await createChat(spaceId)
      if (isStaleSessionEpoch(sessionEpoch)) return null
      const summary = toChatSummary(detail)
      set((state) => ({
        chatsBySpaceId: {
          ...state.chatsBySpaceId,
          [spaceId]: sortByLastUse([...(state.chatsBySpaceId[spaceId] ?? []), summary]),
        },
      }))
      return summary
    } catch (err) {
      if (isStaleSessionEpoch(sessionEpoch)) return null
      const message = err instanceof Error ? err.message : 'Chat konnte nicht erstellt werden'
      set({ error: message })
      return null
    }
  },

  renameChat: async (spaceId: string, chatId: string, title: string) => {
    const sessionEpoch = currentSessionEpoch()
    try {
      await updateChat(chatId, { title })
      if (isStaleSessionEpoch(sessionEpoch)) return
      set((state) => {
        const chats = state.chatsBySpaceId[spaceId]
        if (!chats) return state
        return {
          chatsBySpaceId: {
            ...state.chatsBySpaceId,
            [spaceId]: chats.map((chat) => (chat.id === chatId ? { ...chat, title } : chat)),
          },
        }
      })
    } catch (err) {
      if (isStaleSessionEpoch(sessionEpoch)) return
      const message = err instanceof Error ? err.message : 'Chat konnte nicht umbenannt werden'
      set({ error: message })
    }
  },

  deleteChatFromList: async (spaceId: string, chatId: string) => {
    const sessionEpoch = currentSessionEpoch()
    try {
      await deleteChat(chatId)
      // #573: a deleted chat can never again be the target of a queued settings PATCH or a
      // rollback base - dropping its entries from chatStore's module-level maps here keeps them
      // from growing unbounded for the rest of the session. Done regardless of the session epoch
      // below: the chat really was deleted server-side, so its cache entries must go either way.
      dropChatSettingsCache(chatId)
      if (isStaleSessionEpoch(sessionEpoch)) return
      set((state) => {
        const chats = state.chatsBySpaceId[spaceId]
        if (!chats) return state
        return {
          chatsBySpaceId: {
            ...state.chatsBySpaceId,
            [spaceId]: chats.filter((chat) => chat.id !== chatId),
          },
        }
      })
    } catch (err) {
      if (isStaleSessionEpoch(sessionEpoch)) return
      const message = err instanceof Error ? err.message : 'Chat konnte nicht gelöscht werden'
      set({ error: message })
    }
  },

  setChatPinned: async (spaceId: string, chatId: string, pinned: boolean) => {
    const sessionEpoch = currentSessionEpoch()
    const request = ++pinRequestCounter
    latestPinRequest.set(chatId, request)
    const shown =
      get().chatsBySpaceId[spaceId]?.find((chat) => chat.id === chatId)?.pinnedAt ?? null
    // Without a request in flight, what the list shows is what the server holds.
    if (!pinRequestChains.has(chatId)) confirmedPinnedAt.set(chatId, shown)
    const optimistic = pinned ? new Date().toISOString() : null
    set((state) => ({
      error: null,
      chatsBySpaceId: {
        ...state.chatsBySpaceId,
        [spaceId]: withPinnedAt(state.chatsBySpaceId[spaceId], chatId, optimistic),
      },
    }))
    const send = async () => {
      if (!pinned) {
        await unpinChat(chatId)
        return null
      }
      return (await pinChat(chatId)).pinnedAt ?? optimistic
    }
    const run = (pinRequestChains.get(chatId) ?? Promise.resolve())
      .catch(() => undefined)
      .then(send)
    pinRequestChains.set(chatId, run)
    const isLatest = () => latestPinRequest.get(chatId) === request
    try {
      const confirmed = await run
      confirmedPinnedAt.set(chatId, confirmed)
      if (isStaleSessionEpoch(sessionEpoch) || !isLatest()) return true
      set((state) => ({
        chatsBySpaceId: {
          ...state.chatsBySpaceId,
          [spaceId]: withPinnedAt(state.chatsBySpaceId[spaceId], chatId, confirmed),
        },
      }))
      return true
    } catch (err) {
      if (isStaleSessionEpoch(sessionEpoch) || !isLatest()) return false
      const fallback = pinned
        ? 'Chat konnte nicht angeheftet werden'
        : 'Chat konnte nicht gelöst werden'
      set((state) => ({
        error: err instanceof Error ? err.message : fallback,
        chatsBySpaceId: {
          ...state.chatsBySpaceId,
          [spaceId]: withPinnedAt(
            state.chatsBySpaceId[spaceId],
            chatId,
            confirmedPinnedAt.get(chatId) ?? null,
          ),
        },
      }))
      return false
    } finally {
      if (pinRequestChains.get(chatId) === run) pinRequestChains.delete(chatId)
    }
  },

  loadArchivedChats: async (spaceId: string, page?: number) => {
    const sessionEpoch = currentSessionEpoch()
    const requested = page ?? get().archiveBySpaceId[spaceId]?.page ?? 0
    set({ isLoadingArchive: true, error: null })
    try {
      let result = await listArchivedSpaceChats(spaceId, requested, CHAT_PAGE_SIZE)
      if (result.items.length === 0 && requested > 0 && result.totalElements > 0) {
        const lastPage = Math.ceil(result.totalElements / CHAT_PAGE_SIZE) - 1
        result = await listArchivedSpaceChats(spaceId, lastPage, CHAT_PAGE_SIZE)
      }
      if (isStaleSessionEpoch(sessionEpoch)) return
      set((state) => ({
        isLoadingArchive: false,
        archiveBySpaceId: {
          ...state.archiveBySpaceId,
          [spaceId]: {
            items: result.items,
            page: result.page,
            totalElements: result.totalElements,
          },
        },
      }))
    } catch (err) {
      if (isStaleSessionEpoch(sessionEpoch)) return
      const message =
        err instanceof Error ? err.message : 'Das Chat-Archiv konnte nicht geladen werden'
      set({ error: message, isLoadingArchive: false })
    }
  },

  setChatArchived: async (spaceId: string, chatId: string, archived: boolean) => {
    const sessionEpoch = currentSessionEpoch()
    set({ error: null })
    try {
      // Pinning takes a chat out of the archive on the server, so a pin request still under way
      // for this chat must reach the server before the archive request does.
      await pinRequestChains.get(chatId)?.catch(() => undefined)
      const summary = archived ? await archiveChat(chatId) : await unarchiveChat(chatId)
      if (isStaleSessionEpoch(sessionEpoch)) return false
      set((state) => {
        const chats = state.chatsBySpaceId[spaceId]
        const next = archived
          ? withoutChat(chats, chatId)
          : chats && sortByLastUse([...chats.filter((chat) => chat.id !== chatId), summary])
        return { chatsBySpaceId: { ...state.chatsBySpaceId, [spaceId]: next } }
      })
      useChatStore.getState().applyArchivedAt(chatId, summary.archivedAt ?? null)
      if (get().archiveBySpaceId[spaceId]) await get().loadArchivedChats(spaceId)
      return true
    } catch (err) {
      if (isStaleSessionEpoch(sessionEpoch)) return false
      const fallback = archived
        ? 'Chat konnte nicht archiviert werden'
        : 'Chat konnte nicht aus dem Archiv zurückgeholt werden'
      set({ error: err instanceof Error ? err.message : fallback })
      return false
    }
  },

  applyBulkAction: async (spaceId: string, action: ChatBulkAction, chatIds: string[]) => {
    const sessionEpoch = currentSessionEpoch()
    set({ error: null })
    try {
      const { chatIds: applied } = await applyChatBulkAction(spaceId, action, chatIds)
      if (action === 'DELETE') applied.forEach((chatId) => dropChatSettingsCache(chatId))
      if (isStaleSessionEpoch(sessionEpoch)) return null
      if (action !== 'DELETE') {
        const archivedAt = action === 'ARCHIVE' ? new Date().toISOString() : null
        applied.forEach((chatId) => useChatStore.getState().applyArchivedAt(chatId, archivedAt))
      }
      await Promise.all([get().loadChats(spaceId), get().loadArchivedChats(spaceId)])
      return applied
    } catch (err) {
      if (isStaleSessionEpoch(sessionEpoch)) return null
      const fallback = 'Die Aktion konnte nicht ausgeführt werden'
      set({ error: err instanceof Error ? err.message : fallback })
      return null
    }
  },

  chatReturnedFromArchive: async (spaceId: string) => {
    await get().loadChats(spaceId)
    if (get().archiveBySpaceId[spaceId]) await get().loadArchivedChats(spaceId)
  },

  upsertChat: (spaceId: string, chat: ChatSummary) =>
    set((state) => {
      const existing = state.chatsBySpaceId[spaceId] ?? []
      // A summary built from a ChatDetail carries no pinnedAt; the person's pin must survive it.
      const next = existing.some((c) => c.id === chat.id)
        ? existing.map((c) =>
            c.id === chat.id ? { ...chat, pinnedAt: chat.pinnedAt ?? c.pinnedAt } : c,
          )
        : [...existing, chat]
      return { chatsBySpaceId: { ...state.chatsBySpaceId, [spaceId]: sortByLastUse(next) } }
    }),

  touchChat: (spaceId: string, chatId: string, updatedAt: string) =>
    set((state) => {
      const existing = state.chatsBySpaceId[spaceId]
      if (!existing || !existing.some((chat) => chat.id === chatId)) return state
      const next = existing.map((chat) => (chat.id === chatId ? { ...chat, updatedAt } : chat))
      return { chatsBySpaceId: { ...state.chatsBySpaceId, [spaceId]: sortByLastUse(next) } }
    }),

  updateChatTitle: (spaceId: string, chatId: string, title: string | null) =>
    set((state) => {
      const existing = state.chatsBySpaceId[spaceId]
      if (!existing || !existing.some((chat) => chat.id === chatId)) return state
      const next = existing.map((chat) => (chat.id === chatId ? { ...chat, title } : chat))
      return { chatsBySpaceId: { ...state.chatsBySpaceId, [spaceId]: next } }
    }),
}))
