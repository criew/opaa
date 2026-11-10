import type { ChatDetail } from '@/shared/api/types'
import { buildCitationIndex } from '@/shared/ui/markdown/citations'
import { toChatMessage } from '../stores/chatDetail'
import { chatTitle } from './chatListSections'
import type { OutwardPolicy } from '@/shared/ui/markdown/outwardMarkdown'
import { answerForOutside } from '@/shared/ui/markdown/outwardMarkdown'
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
 * in a document. Cited sources without a marker follow as a list. The Gesprächsnotiz, when it has
 * points, stands between the title and the first question. The note closes the document. The
 * whole document, title and questions included, goes through {@link answerForOutside}.
 */
export function chatAsMarkdown(
  chat: ChatDetail,
  note: string,
  exportedAt: Date,
  policy: OutwardPolicy = {},
): string {
  const parts = [
    `# ${chatTitle(chat)}`,
    `Chat begonnen am ${exportTimestamp(new Date(chat.createdAt))} · exportiert am ${exportTimestamp(exportedAt)}`,
  ]
  const notePoints = (chat.noteItems ?? []).map((item) => item.text.replace(/\s+/g, ' ').trim())
  if (notePoints.length > 0) {
    parts.push('## Gesprächsnotiz', notePoints.map((point) => `- ${point}`).join('\n'))
  }
  let answerNumber = 0
  for (const message of chat.messages.map(toChatMessage)) {
    const at = exportTimestamp(message.timestamp)
    if (message.role === 'user') {
      parts.push(`## Frage · ${at}`, message.content.trim())
      continue
    }
    answerNumber += 1
    const citations = buildCitationIndex(message.content, message.sources)
    const body = answerWithSources(message.content, citations, {
      ...policy,
      labelPrefix: `${answerNumber}-`,
    })
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
  return `${markdownWithNote(answerForOutside(parts.join('\n\n'), policy).trim(), note)}\n`
}

export { exportFileName } from './exportFileName'
