import { describe, expect, it } from 'vitest'
import type { ChatDetail, ChatMessageResponse } from '@/shared/api/types'
import { chatAsMarkdown, exportFileName, exportTimestamp } from './chatExport'

const ASKED = '2026-03-05T09:15:00Z'
const ANSWERED = '2026-03-05T09:15:30Z'
const EXPORTED = new Date('2026-03-06T07:00:00Z')
const NOTE = 'Antworten werden von einer KI erzeugt. – OPAA, 06.03.2026'
const BESCHEID = '11111111-1111-1111-1111-111111111111'
const MERKBLATT = '22222222-2222-2222-2222-222222222222'

function message(overrides: Partial<ChatMessageResponse>): ChatMessageResponse {
  return {
    id: crypto.randomUUID(),
    chatId: 'chat-1',
    role: 'USER',
    content: '',
    generatedByAi: false,
    createdAt: ASKED,
    ...overrides,
  }
}

function chat(
  messages: ChatMessageResponse[],
  title: string | null = 'Widerspruchsfrist',
): ChatDetail {
  return {
    id: 'chat-1',
    spaceId: 'space-1',
    authorId: 'user-1',
    title,
    useKnowledge: true,
    status: 'PRIVATE',
    messages,
    createdAt: ASKED,
    updatedAt: ANSWERED,
  }
}

function answer(content: string, sources: ChatMessageResponse['sources']): ChatMessageResponse {
  return message({
    role: 'ASSISTANT',
    content,
    generatedByAi: true,
    sources,
    createdAt: ANSWERED,
  })
}

type MessageSource = NonNullable<ChatMessageResponse['sources']>[number]

function source(
  fileName: string,
  documentId: string,
  cited: boolean,
  location?: string,
): MessageSource {
  return {
    fileName,
    documentId,
    cited,
    citationValid: true,
    privateSource: false,
    relevanceScore: 0.9,
    matchCount: 1,
    ...(location ? { chunkLocations: [{ chunkIndex: 3, location }] } : {}),
  }
}

describe('chatAsMarkdown', () => {
  it('puts the title on top and every question and answer with its timestamp in order', () => {
    const markdown = chatAsMarkdown(
      chat([message({ content: 'Wie lang ist die Frist?' }), answer('Ein **Monat**.', [])]),
      NOTE,
      EXPORTED,
    )

    const asked = exportTimestamp(new Date(ASKED))
    const answered = exportTimestamp(new Date(ANSWERED))
    expect(markdown.startsWith('# Widerspruchsfrist\n\n')).toBe(true)
    expect(markdown).toContain(
      `Chat begonnen am ${asked} · exportiert am ${exportTimestamp(EXPORTED)}`,
    )
    expect(markdown.indexOf(`## Frage · ${asked}\n\nWie lang ist die Frist?`)).toBeLessThan(
      markdown.indexOf(`## Antwort · ${answered}\n\nEin **Monat**.`),
    )
    expect(markdown.indexOf(`## Antwort · ${answered}`)).toBeGreaterThan(0)
  })

  it('turns citation markers into footnotes with file name and location, unique per answer', () => {
    const first = `Die Frist beträgt einen Monat 【source: ${BESCHEID}#3 | Bescheid.pdf】.`
    const second = `Sie beginnt mit Zugang 【source: ${MERKBLATT}#0 | Merkblatt.docx】.`
    const markdown = chatAsMarkdown(
      chat([
        answer(first, [source('Bescheid.pdf', BESCHEID, true, 'S. 3')]),
        answer(second, [source('Merkblatt.docx', MERKBLATT, true)]),
      ]),
      NOTE,
      EXPORTED,
    )

    expect(markdown).toContain('Die Frist beträgt einen Monat[^1-1].')
    expect(markdown).toContain('[^1-1]: Bescheid.pdf, S. 3')
    expect(markdown).toContain('Sie beginnt mit Zugang[^2-1].')
    expect(markdown).toContain('[^2-1]: Merkblatt.docx')
    expect(markdown).not.toContain('【')
  })

  it('lists cited sources the text never marks, and leaves out the merely checked ones', () => {
    const markdown = chatAsMarkdown(
      chat([
        answer('Gilt so.', [
          source('Vermerk.pdf', BESCHEID, true),
          source('Anlage.pdf', MERKBLATT, false),
        ]),
      ]),
      NOTE,
      EXPORTED,
    )

    expect(markdown).toContain('Weitere zitierte Quellen:\n- Vermerk.pdf')
    expect(markdown).not.toContain('Anlage.pdf')
  })

  it('ends with the note under a rule', () => {
    const markdown = chatAsMarkdown(chat([message({ content: 'Frage' })]), NOTE, EXPORTED)

    expect(markdown.endsWith(`\n\n---\n\n${NOTE}\n`)).toBe(true)
  })

  it('names an untitled chat like the chat list and says when it is empty', () => {
    const markdown = chatAsMarkdown(chat([], null), NOTE, EXPORTED)

    expect(markdown.startsWith('# Unbenannter Chat\n')).toBe(true)
    expect(markdown).toContain('Dieser Chat enthält noch keine Nachrichten.')
  })
})

describe('exportFileName', () => {
  it('uses the title without characters a file system rejects', () => {
    expect(exportFileName('Antrag: 12/4-2026 "neu"?', 'md')).toBe('Antrag 12 4-2026 neu.md')
    expect(exportFileName(null, 'pdf')).toBe('Unbenannter Chat.pdf')
    expect(exportFileName('..', 'pdf')).toBe('Chat.pdf')
    expect(exportFileName('x'.repeat(200), 'md')).toBe(`${'x'.repeat(80)}.md`)
  })
})
