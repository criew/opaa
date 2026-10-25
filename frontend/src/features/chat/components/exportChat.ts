import { useBrandingStore } from '@/shared/branding/brandingStore'
import { notify } from '@/shared/stores/notificationStore'
import { exportChatPdf, getChat } from '../api/chatApi'
import { chatAsMarkdown, exportFileName } from './chatExport'
import { copyNote } from './copyFormats'

export type ChatExportFormat = 'markdown' | 'pdf'

/** How long a download's object URL stays valid; revoking at once could cut the download off. */
const OBJECT_URL_LIFETIME_MS = 60_000

function download(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob)
  try {
    const link = document.createElement('a')
    link.href = url
    link.download = fileName
    link.rel = 'noopener'
    document.body.appendChild(link)
    link.click()
    link.remove()
  } finally {
    window.setTimeout(() => URL.revokeObjectURL(url), OBJECT_URL_LIFETIME_MS)
  }
}

/**
 * Saves one chat as a file: Markdown is built here from the chat as the server returns it, the PDF
 * comes from the server. A failure is reported as a notification, never thrown.
 */
export async function exportChat(
  chat: { id: string; title?: string | null },
  format: ChatExportFormat,
): Promise<void> {
  try {
    if (format === 'markdown') {
      const detail = await getChat(chat.id)
      const { branding } = useBrandingStore.getState()
      const exportedAt = new Date()
      const note = copyNote(branding.aiNotice, branding.productName, exportedAt)
      const markdown = chatAsMarkdown(detail, note, exportedAt)
      download(
        new Blob([markdown], { type: 'text/markdown;charset=utf-8' }),
        exportFileName(detail.title, 'md'),
      )
    } else {
      download(await exportChatPdf(chat.id), exportFileName(chat.title, 'pdf'))
    }
  } catch (err) {
    const fallback = 'Der Chat konnte nicht exportiert werden.'
    notify(err instanceof Error && err.message ? err.message : fallback, 'error')
  }
}
