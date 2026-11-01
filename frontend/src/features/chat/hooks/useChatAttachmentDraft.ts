import { useCallback, useEffect, useRef, useState } from 'react'
import type { ChatAttachment, ChatAttachmentRef } from '@/shared/api/types'
import {
  CHAT_ATTACHMENTS_PER_MESSAGE,
  deleteChatAttachment,
  getChatAttachment,
  uploadChatAttachment,
} from '../api/chatAttachmentApi'
import { responseStatus } from '../stores/chatRefusals'

export type DraftAttachmentPhase = 'uploading' | 'processing' | 'ready' | 'failed'

/** One file the person put next to the question they are writing. */
export interface DraftAttachment {
  key: string
  fileName: string
  sizeBytes: number
  phase: DraftAttachmentPhase
  /** The server's attachment once the upload was accepted. */
  id?: string
  /** Why the file cannot be used, in German. */
  error?: string
}

/** How often a file still being read is asked for again. */
export const ATTACHMENT_POLL_MS = 1500

/**
 * Uploads running at once. The server reads at most a few attachments of one account at a time
 * and refuses more with 429, so a larger drop waits here instead of failing there.
 */
export const PARALLEL_UPLOADS = 3

export const TOO_MANY_ATTACHMENTS_NOTE = `Höchstens ${CHAT_ATTACHMENTS_PER_MESSAGE} Anhänge je Frage.`

let keySequence = 0

function phaseOf(attachment: ChatAttachment): DraftAttachmentPhase {
  if (attachment.status === 'READY') return 'ready'
  if (attachment.status === 'FAILED') return 'failed'
  return 'processing'
}

/**
 * The attachments of the question being written: uploaded to `spaceId` the moment they are added,
 * followed while the server reads them, and handed to the question with {@link take}. Removing one
 * deletes it on the server; one never sent is deleted there after its retention anyway.
 */
export function useChatAttachmentDraft(spaceId: string | null) {
  const [items, setItems] = useState<DraftAttachment[]>([])
  const [notice, setNotice] = useState<string | null>(null)
  const itemsRef = useRef(items)
  useEffect(() => {
    itemsRef.current = items
  }, [items])

  const update = useCallback((key: string, change: Partial<DraftAttachment>) => {
    setItems((current) => current.map((item) => (item.key === key ? { ...item, ...change } : item)))
  }, [])

  // Every file the server is still reading is asked for again after a pause; each answer changes
  // the draft and schedules the next round, until none is left reading.
  useEffect(() => {
    const reading = items.filter((item) => item.phase === 'processing' && item.id)
    if (reading.length === 0) return
    const timer = setTimeout(() => {
      reading.forEach((item) => {
        getChatAttachment(item.id!).then(
          (attachment) =>
            update(item.key, {
              phase: phaseOf(attachment),
              error: attachment.failureReason ?? undefined,
            }),
          (err: unknown) => {
            // a passing network or server failure is asked again in the next round
            if (responseStatus(err) !== 404) return
            update(item.key, { phase: 'failed', error: 'Der Anhang ist nicht mehr vorhanden.' })
          },
        )
      })
    }, ATTACHMENT_POLL_MS)
    return () => clearTimeout(timer)
  }, [items, update])

  const queue = useRef<Array<{ item: DraftAttachment; file: File; spaceId: string }>>([])
  const running = useRef(0)
  // keys removed from the draft: a queued upload is skipped, a finished one deleted again
  const removed = useRef(new Set<string>())
  const currentSpace = useRef(spaceId)
  useEffect(() => {
    currentSpace.current = spaceId
  }, [spaceId])
  const pump = useCallback(
    function pumpQueue() {
      while (running.current < PARALLEL_UPLOADS && queue.current.length > 0) {
        const { item, file, spaceId: target } = queue.current.shift()!
        if (removed.current.has(item.key) || target !== currentSpace.current) continue
        running.current += 1
        void uploadChatAttachment(target, file)
          .then(
            (attachment) => {
              if (removed.current.has(item.key) || target !== currentSpace.current) {
                // removed while it was uploading: it is not wanted anymore
                void deleteChatAttachment(attachment.id).catch(() => {})
                return
              }
              update(item.key, {
                id: attachment.id,
                phase: phaseOf(attachment),
                error: attachment.failureReason ?? undefined,
              })
            },
            (err: unknown) =>
              update(item.key, {
                phase: 'failed',
                error:
                  err instanceof Error ? err.message : 'Die Datei konnte nicht hochgeladen werden.',
              }),
          )
          .finally(() => {
            running.current -= 1
            pumpQueue()
          })
      }
    },
    [update],
  )

  // Attachments belong to one space; a draft does not travel along to another.
  const [seenSpaceId, setSeenSpaceId] = useState(spaceId)
  if (seenSpaceId !== spaceId) {
    setSeenSpaceId(spaceId)
    setItems([])
    setNotice(null)
  }

  const add = useCallback(
    (files: File[]) => {
      if (!spaceId || files.length === 0) return
      const room = CHAT_ATTACHMENTS_PER_MESSAGE - itemsRef.current.length
      setNotice(files.length > room ? TOO_MANY_ATTACHMENTS_NOTE : null)
      const accepted = files.slice(0, Math.max(room, 0))
      const added = accepted.map((file): DraftAttachment => ({
        key: `attachment-${++keySequence}`,
        fileName: file.name,
        sizeBytes: file.size,
        phase: 'uploading',
      }))
      setItems((current) => [...current, ...added])
      added.forEach((item, index) => queue.current.push({ item, file: accepted[index], spaceId }))
      pump()
    },
    [spaceId, pump],
  )

  const remove = useCallback((key: string) => {
    const item = itemsRef.current.find((entry) => entry.key === key)
    removed.current.add(key)
    setItems((current) => current.filter((entry) => entry.key !== key))
    setNotice(null)
    if (item?.id) void deleteChatAttachment(item.id).catch(() => {})
  }, [])

  /** The ready attachments for the question being sent; the draft is empty afterwards. */
  const take = useCallback((): ChatAttachmentRef[] => {
    const refs = itemsRef.current
      .filter((item) => item.phase === 'ready' && item.id)
      .map((item) => ({ id: item.id!, fileName: item.fileName, sizeBytes: item.sizeBytes }))
    setItems([])
    setNotice(null)
    return refs
  }, [])

  /** Puts the attachments of a refused question back, before any added since. */
  const restore = useCallback((refs: ChatAttachmentRef[]) => {
    if (refs.length === 0) return
    setItems((current) => [
      ...refs.map((ref): DraftAttachment => ({
        key: `attachment-${++keySequence}`,
        id: ref.id,
        fileName: ref.fileName,
        sizeBytes: ref.sizeBytes ?? 0,
        phase: 'ready',
      })),
      ...current.filter((item) => !refs.some((ref) => ref.id === item.id)),
    ])
  }, [])

  const busy = items.some((item) => item.phase === 'uploading' || item.phase === 'processing')
  const blocked = items.some((item) => item.phase === 'failed')
  return { items, notice, add, remove, take, restore, busy, blocked }
}
