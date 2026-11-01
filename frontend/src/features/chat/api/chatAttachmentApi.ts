import type { ChatAttachment, ChatAttachmentRef, LibraryDocumentResponse } from '@/shared/api/types'
import { apiClient as client, normalizeError } from '@/shared/api/api'

/** The most attachments one question carries; the backend refuses more with 400. */
export const CHAT_ATTACHMENTS_PER_MESSAGE = 10

/**
 * Uploads `file` as an attachment of the caller in `spaceId`. The backend waits a few seconds for
 * the text and answers READY or FAILED, or PROCESSING when reading takes longer.
 */
export async function uploadChatAttachment(spaceId: string, file: File): Promise<ChatAttachment> {
  try {
    const formData = new FormData()
    formData.append('file', file)
    const { data } = await client.post<ChatAttachment>(
      `/v1/spaces/${spaceId}/chat-attachments`,
      formData,
      { headers: { 'Content-Type': 'multipart/form-data' } },
    )
    return data
  } catch (err) {
    normalizeError(err, 'upload')
  }
}

export async function getChatAttachment(attachmentId: string): Promise<ChatAttachment> {
  try {
    const { data } = await client.get<ChatAttachment>(`/v1/chat-attachments/${attachmentId}`)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function listMyChatAttachments(): Promise<ChatAttachment[]> {
  try {
    const { data } = await client.get<ChatAttachment[]>('/v1/chat-attachments')
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function deleteChatAttachment(attachmentId: string): Promise<void> {
  try {
    await client.delete(`/v1/chat-attachments/${attachmentId}`)
  } catch (err) {
    normalizeError(err)
  }
}

export async function copyChatAttachmentToLibrary(
  attachmentId: string,
  libraryId: string,
): Promise<LibraryDocumentResponse> {
  try {
    const { data } = await client.post<LibraryDocumentResponse>(
      `/v1/chat-attachments/${attachmentId}/library-copy`,
      { libraryId },
    )
    return data
  } catch (err) {
    normalizeError(err, 'upload')
  }
}

/**
 * Saves the original of an attachment as a file. Bearer-authenticated like every endpoint, so a
 * plain link cannot reach it (ADR-0005); never opened inline, which keeps any active content of
 * the file out of the app's origin.
 */
export async function downloadChatAttachment(attachment: ChatAttachmentRef): Promise<void> {
  let blob: Blob
  try {
    const response = await client.get<Blob>(`/v1/chat-attachments/${attachment.id}/content`, {
      responseType: 'blob',
    })
    blob = response.data
  } catch (err) {
    normalizeError(err)
  }
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = attachment.fileName
  document.body.appendChild(link)
  link.click()
  link.remove()
  setTimeout(() => URL.revokeObjectURL(url), 60_000)
}
