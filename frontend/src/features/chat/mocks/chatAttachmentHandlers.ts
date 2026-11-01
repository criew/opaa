import { http, HttpResponse } from 'msw'
import type { ChatAttachment } from '@/shared/api/types'

/** The caller's chat attachments of the mock backend, newest first. */
let mockChatAttachments: ChatAttachment[] = []

export function resetMockChatAttachments() {
  mockChatAttachments = []
}

export function seedMockChatAttachments(attachments: ChatAttachment[]) {
  mockChatAttachments = [...attachments]
}

function withId(id: unknown) {
  return mockChatAttachments.find((attachment) => attachment.id === String(id))
}

const NOT_FOUND = { error: 'Anhang nicht gefunden' }

export const chatAttachmentHandlers = [
  // Mirrors ChatAttachmentService#upload: every accepted file is read at once in the mock.
  http.post('/api/v1/spaces/:spaceId/chat-attachments', async ({ params, request }) => {
    const form = await request.formData()
    const file = form.get('file')
    if (!(file instanceof File) || file.size === 0) {
      return HttpResponse.json({ error: 'Datei ist erforderlich' }, { status: 400 })
    }
    const now = new Date()
    const attachment: ChatAttachment = {
      id: crypto.randomUUID(),
      spaceId: String(params.spaceId),
      chatId: null,
      chatTitle: null,
      fileName: file.name,
      contentType: file.type || 'application/octet-stream',
      sizeBytes: file.size,
      status: 'READY',
      failureReason: null,
      mode: 'FULL_TEXT',
      createdAt: now.toISOString(),
      deletionDueAt: new Date(now.getTime() + 24 * 3600 * 1000).toISOString(),
    }
    mockChatAttachments = [attachment, ...mockChatAttachments]
    return HttpResponse.json(attachment, { status: 201 })
  }),

  http.get('/api/v1/chat-attachments', () => HttpResponse.json(mockChatAttachments)),

  http.get('/api/v1/chat-attachments/:attachmentId', ({ params }) => {
    const attachment = withId(params.attachmentId)
    return attachment
      ? HttpResponse.json(attachment)
      : HttpResponse.json(NOT_FOUND, { status: 404 })
  }),

  http.delete('/api/v1/chat-attachments/:attachmentId', ({ params }) => {
    if (!withId(params.attachmentId)) return HttpResponse.json(NOT_FOUND, { status: 404 })
    mockChatAttachments = mockChatAttachments.filter(
      (attachment) => attachment.id !== String(params.attachmentId),
    )
    return new HttpResponse(null, { status: 204 })
  }),

  http.get('/api/v1/chat-attachments/:attachmentId/content', ({ params }) => {
    const attachment = withId(params.attachmentId)
    if (!attachment) return HttpResponse.json(NOT_FOUND, { status: 404 })
    return new HttpResponse(`Inhalt von ${attachment.fileName}`, {
      headers: { 'Content-Type': 'text/plain' },
    })
  }),

  http.post('/api/v1/chat-attachments/:attachmentId/library-copy', async ({ params, request }) => {
    const attachment = withId(params.attachmentId)
    if (!attachment) return HttpResponse.json(NOT_FOUND, { status: 404 })
    const body = (await request.json()) as { libraryId: string }
    return HttpResponse.json(
      {
        id: crypto.randomUUID(),
        libraryId: body.libraryId,
        fileName: attachment.fileName,
        status: 'PENDING',
      },
      { status: 201 },
    )
  }),
]
