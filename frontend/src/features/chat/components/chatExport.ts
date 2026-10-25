import type { ChatDetail } from '@/shared/api/types'
import { buildCitationIndex } from '@/shared/ui/markdown/citations'
import { toChatMessage } from '../stores/chatDetail'
import { chatTitle } from './chatListSections'
import { answerWithSources, markdownWithNote } from './copyFormats'

/** Date and time as the export prints them, in the browser's zone: "05.03.2026, 10:15 Uhr". */
export function exportTimestamp(date: Date): string {
  const text = date.toLocaleString('de-DE', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
  return `${text} Uhr`
}

/**
 * The chat as one Markdown document: the title as heading, then every question and answer with
 * its timestamp. An answer keeps its Markdown and carries its sources as footnotes, the numbers
 * the chat shows; labels are prefixed per answer (`[^2-1]`), because footnote labels are global
 * in a document. Cited sources without a marker follow as a list. The note closes the document.
 */
export function chatAsMarkdown(chat: ChatDetail, note: string, exportedAt: Date): string {
  const parts = [
    `# ${chatTitle(chat)}`,
    `Chat begonnen am ${exportTimestamp(new Date(chat.createdAt))} · exportiert am ${exportTimestamp(exportedAt)}`,
  ]
  let answerNumber = 0
  for (const message of chat.messages.map(toChatMessage)) {
    const at = exportTimestamp(message.timestamp)
    if (message.role === 'user') {
      parts.push(`## Frage · ${at}`, message.content.trim())
      continue
    }
    answerNumber += 1
    const citations = buildCitationIndex(message.content, message.sources)
    const prefix = answerNumber
    const body = answerWithSources(message.content, citations).replace(
      /\[\^(\d+)\]/g,
      (_, number: string) => `[^${prefix}-${number}]`,
    )
    parts.push(`## Antwort · ${at}`, body)
    const unmarked = citations.docs.filter((doc) => doc.numbers.length === 0)
    if (unmarked.length > 0) {
      parts.push(
        ['Weitere zitierte Quellen:', ...unmarked.map((doc) => `- ${doc.fileName}`)].join('\n'),
      )
    }
  }
  if (chat.messages.length === 0) {
    parts.push('Dieser Chat enthält noch keine Nachrichten.')
  }
  return `${markdownWithNote(parts.join('\n\n'), note)}\n`
}

/** Characters a file system rejects, plus control characters - the server's rule for the PDF. */
// eslint-disable-next-line no-control-regex
const UNSAFE_FILE_NAME = /[\\/:*?"<>|\u0000-\u001f\u007f]/g

/**
 * The download name of an export: the chat's title, cleaned like the server cleans it - without
 * invisible format characters and cut to 80 code points.
 */
export function exportFileName(title: string | null | undefined, extension: 'md' | 'pdf'): string {
  let name = chatTitle({ title })
    .replace(/\p{Cf}/gu, '')
    .replace(UNSAFE_FILE_NAME, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .replace(/^\.+/, '')
    .trim()
  const codePoints = Array.from(name)
  if (codePoints.length > 80) name = codePoints.slice(0, 80).join('').trim()
  return `${name || 'Chat'}.${extension}`
}
