import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ChatDetail } from '@/shared/api/types'
import { useNotificationStore } from '@/shared/stores/notificationStore'
import { exportChat } from './exportChat'

const getChat = vi.fn()
const exportChatPdf = vi.fn()

vi.mock('../api/chatApi', () => ({
  getChat: (chatId: string) => getChat(chatId) as Promise<ChatDetail>,
  exportChatPdf: (chatId: string) => exportChatPdf(chatId) as Promise<Blob>,
}))

const detail: ChatDetail = {
  id: 'chat-1',
  spaceId: 'space-1',
  authorId: 'user-1',
  title: 'Frist: Widerspruch',
  useKnowledge: true,
  status: 'PRIVATE',
  messages: [
    {
      id: 'm-1',
      chatId: 'chat-1',
      role: 'USER',
      content: 'Wie lang?',
      generatedByAi: false,
      createdAt: '2026-03-05T09:15:00Z',
    },
  ],
  createdAt: '2026-03-05T09:15:00Z',
  updatedAt: '2026-03-05T09:15:00Z',
}

describe('exportChat', () => {
  let downloads: { name: string; blob: Blob }[]

  beforeEach(() => {
    downloads = []
    getChat.mockReset()
    exportChatPdf.mockReset()
    useNotificationStore.setState({ queue: [] })
    const blobs = new Map<string, Blob>()
    vi.spyOn(URL, 'createObjectURL').mockImplementation((blob) => {
      const url = `blob:test-${blobs.size}`
      blobs.set(url, blob as Blob)
      return url
    })
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      downloads.push({ name: this.download, blob: blobs.get(this.href) as Blob })
    })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('builds the Markdown from the chat and downloads it under the chat title', async () => {
    getChat.mockResolvedValue(detail)

    await exportChat({ id: 'chat-1', title: 'Frist: Widerspruch' }, 'markdown')

    expect(getChat).toHaveBeenCalledWith('chat-1')
    expect(downloads).toHaveLength(1)
    expect(downloads[0].name).toBe('Frist Widerspruch.md')
    const text = await downloads[0].blob.text()
    expect(text).toContain('# Frist: Widerspruch')
    expect(text).toContain('Wie lang?')
    expect(exportChatPdf).not.toHaveBeenCalled()
  })

  it('downloads the PDF the server built', async () => {
    const pdf = new Blob(['%PDF-1.7'], { type: 'application/pdf' })
    exportChatPdf.mockResolvedValue(pdf)

    await exportChat({ id: 'chat-1', title: 'Frist: Widerspruch' }, 'pdf')

    expect(exportChatPdf).toHaveBeenCalledWith('chat-1')
    expect(downloads).toEqual([{ name: 'Frist Widerspruch.pdf', blob: pdf }])
  })

  it('reports a refusal as an error notification instead of throwing', async () => {
    exportChatPdf.mockRejectedValue(new Error('Sie sind kein Mitglied dieses Space mehr'))

    await exportChat({ id: 'chat-1', title: 'T' }, 'pdf')

    expect(downloads).toHaveLength(0)
    expect(useNotificationStore.getState().queue).toEqual([
      expect.objectContaining({
        message: 'Sie sind kein Mitglied dieses Space mehr',
        severity: 'error',
      }),
    ])
  })
})
