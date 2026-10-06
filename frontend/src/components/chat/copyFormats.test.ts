import { describe, expect, test } from 'vitest'
import { buildCitationIndex } from './citations'
import { answerAsMarkdown, answerAsPlainText, answerWithSources } from './copyFormats'
import type { SourceReference } from '../../types/api'

const ANSWER =
  '## Gebühren\n\nEin Personalausweis kostet **42,60 Euro** 【source: doc-a#0 | 001_personalausweis.md】.\n' +
  '- Passbild 【source: doc-b#2 | 016_familie.md】【source: doc-b#2 | 016_familie.md】\n' +
  '- Termin im [Bürgerbüro](https://example.test/termin) 【source: doc-a#0 | 001_personalausweis.md】'

const SOURCES: SourceReference[] = [
  {
    fileName: '001_personalausweis.md',
    documentId: 'doc-a',
    relevanceScore: 1,
    matchCount: 1,
    cited: true,
    indexedAt: null,
    citationValid: true,
    privateSource: false,
  },
  {
    fileName: '016_familie.md',
    documentId: 'doc-b',
    relevanceScore: 0.5,
    matchCount: 1,
    cited: true,
    indexedAt: null,
    citationValid: true,
    privateSource: false,
    chunkLocations: [{ chunkIndex: 2, location: 'Abschn. Unterlagen' }],
  },
]

describe('answerAsMarkdown', () => {
  test('keeps the Markdown and drops every marker with the space before it', () => {
    expect(answerAsMarkdown(ANSWER)).toBe(
      '## Gebühren\n\nEin Personalausweis kostet **42,60 Euro**.\n' +
        '- Passbild\n' +
        '- Termin im [Bürgerbüro](https://example.test/termin)',
    )
  })
})

describe('answerWithSources', () => {
  test('turns markers into Markdown footnotes and lists the cited documents with their Fundort', () => {
    const citations = buildCitationIndex(ANSWER, SOURCES)

    expect(answerWithSources(ANSWER, citations)).toBe(
      '## Gebühren\n\nEin Personalausweis kostet **42,60 Euro**[^1].\n' +
        '- Passbild[^2]\n' +
        '- Termin im [Bürgerbüro](https://example.test/termin)[^1]\n\n' +
        '[^1]: 001_personalausweis.md\n' +
        '[^2]: 016_familie.md, Abschn. Unterlagen',
    )
  })

  test('adds no source list to an answer without markers', () => {
    const content = 'Dazu steht nichts in den Beständen.'
    expect(answerWithSources(content, buildCitationIndex(content, []))).toBe(content)
  })
})

// regression guard for #2298: a marker the backend could not read never reaches a copy
describe('malformed markers', () => {
  const content =
    'Nur Formate der Liste. 【source: 05_schulung.pptx#2 | 05_schulung.pptx】【source: a#a】'

  test('are dropped from the Markdown copy', () => {
    expect(answerAsMarkdown(content)).toBe('Nur Formate der Liste.')
  })

  test('never take the text up to the next marker when one is not closed', () => {
    const unclosed = 'Erster 【source: doc-a#0 | a.md\n\nZweiter Absatz. 【source: doc-b#2 | b.md】'
    expect(answerAsMarkdown(unclosed)).toBe('Erster 【source: doc-a#0 | a.md\n\nZweiter Absatz.')
  })

  test('are dropped from the copy with sources and get no footnote', () => {
    expect(answerWithSources(content, buildCitationIndex(content, SOURCES))).toBe(
      'Nur Formate der Liste.',
    )
  })
})

// regression guard for #2300: no copy keeps the parentheses a marker stood in
describe('bracketed markers', () => {
  const content =
    'Ein Personalausweis kostet 42,60 Euro (【source: doc-a#0 | 001_personalausweis.md】). ' +
    'Siehe (S. 4 【source: doc-b#2 | 016_familie.md】).'

  test('leave no empty parentheses in the Markdown copy', () => {
    expect(answerAsMarkdown(content)).toBe('Ein Personalausweis kostet 42,60 Euro. Siehe (S. 4).')
  })

  test('become a bare footnote in the copy with sources', () => {
    expect(answerWithSources(content, buildCitationIndex(content, SOURCES))).toBe(
      'Ein Personalausweis kostet 42,60 Euro[^1]. Siehe (S. 4[^2]).\n\n' +
        '[^1]: 001_personalausweis.md\n' +
        '[^2]: 016_familie.md, Abschn. Unterlagen',
    )
  })
})

describe('answerAsPlainText', () => {
  test('removes the Markdown syntax but keeps structure readable', () => {
    expect(answerAsPlainText(ANSWER)).toBe(
      'Gebühren\n\nEin Personalausweis kostet 42,60 Euro.\n• Passbild\n• Termin im Bürgerbüro',
    )
  })

  test('turns table rows into tab-separated cells and drops the separator row', () => {
    const table = '| Leistung | Gebühr |\n| --- | ---: |\n| Personalausweis | **42,60 €** |'
    expect(answerAsPlainText(table)).toBe('Leistung\tGebühr\nPersonalausweis\t42,60 €')
  })

  test('keeps code lines verbatim and drops the fences', () => {
    const code = 'Aufruf:\n\n```bash\nopaa --help  # *nicht* kursiv\n```'
    expect(answerAsPlainText(code)).toBe('Aufruf:\n\nopaa --help  # *nicht* kursiv')
  })

  test('leaves underscores inside words alone', () => {
    expect(answerAsPlainText('Datei 001_personalausweis_neu.md und _kursiv_')).toBe(
      'Datei 001_personalausweis_neu.md und kursiv',
    )
  })
})
