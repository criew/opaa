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

  it('prefixes only its own footnotes, never a footnote-like text in the answer or its code', () => {
    const content = [
      `Siehe Fußnote [^5] im Bescheid 【source: ${BESCHEID}#3 | Bescheid.pdf】.`,
      '',
      '```',
      "grep '[^5]' akte.txt",
      '```',
    ].join('\n')
    const markdown = chatAsMarkdown(
      chat([answer(content, [source('Bescheid.pdf', BESCHEID, true, 'S. 3')])]),
      NOTE,
      EXPORTED,
    )

    expect(markdown).toContain('Siehe Fußnote [^5] im Bescheid[^1-1].')
    expect(markdown).toContain("grep '[^5]' akte.txt")
    expect(markdown).not.toContain('[^1-5]')
    expect(markdown).toContain('[^1-1]: Bescheid.pdf, S. 3')
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

  it('puts the Gesprächsnotiz between the title and the first question', () => {
    const detail: ChatDetail = {
      ...chat([message({ content: 'Wie lang?' })]),
      noteItems: [
        { id: 'n1', text: 'Arbeitet im Bürgerbüro', kind: 'RAHMEN', createdAt: ASKED },
        { id: 'n2', text: 'Möchte kurze Antworten', kind: 'ANTWORTFORM', createdAt: ASKED },
      ],
    }

    const markdown = chatAsMarkdown(detail, NOTE, EXPORTED)

    expect(markdown).toContain(
      '## Gesprächsnotiz\n\n- Arbeitet im Bürgerbüro\n- Möchte kurze Antworten\n\n## Frage',
    )
  })

  it('has no Gesprächsnotiz section while the note is empty', () => {
    const markdown = chatAsMarkdown(chat([message({ content: 'Wie lang?' })]), NOTE, EXPORTED)

    expect(markdown).not.toContain('Gesprächsnotiz')
  })

  it('reads text built to make its patterns backtrack in linear time', () => {
    const hostile = [
      '['.repeat(50_000),
      ' *a'.repeat(15_000),
      `a${' '.repeat(50_000)}b`,
      `a${' '.repeat(100_000)}【source:x`,
      `【source:${BESCHEID}#1|x`.repeat(5_000),
      `${'('.repeat(25_000)}【source: ${BESCHEID}#3 | Bescheid.pdf】${')'.repeat(25_000)}`,
    ]
    for (const text of hostile) {
      const started = performance.now()
      chatAsMarkdown(chat([message({ content: text }), answer(text, [])]), NOTE, EXPORTED)
      expect(performance.now() - started).toBeLessThan(2000)
    }
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
    expect(exportFileName('Bescheid\u200B\u202E neu', 'pdf')).toBe('Bescheid neu.pdf')
    expect(exportFileName('\u{1F600}'.repeat(100), 'md')).toBe(`${'\u{1F600}'.repeat(80)}.md`)
  })
})
