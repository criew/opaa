import { describe, expect, it } from 'vitest'
import { anchorPlace, anchorQuote } from './findingText'

describe('anchorPlace', () => {
  it('names page, page range, slide or rows, with the section after it', () => {
    expect(anchorPlace({ pageFrom: 2 })).toBe('Seite 2')
    expect(anchorPlace({ pageFrom: 2, pageTo: 3, path: ['1 Kapitel', '1.2 Fristen'] })).toBe(
      'Seiten 2–3 · 1.2 Fristen',
    )
    expect(anchorPlace({ slide: 4 })).toBe('Folie 4')
    expect(anchorPlace({ rowFrom: 5, rowTo: 5 })).toBe('Zeile 5')
    expect(anchorPlace({ rowFrom: 5, rowTo: 9 })).toBe('Zeilen 5–9')
    expect(anchorPlace({ path: ['2. Fristen'] })).toBe('2. Fristen')
  })

  it('is null without a place', () => {
    expect(anchorPlace({ quoteStart: 'x' })).toBeNull()
    expect(anchorPlace(null)).toBeNull()
    expect(anchorPlace(undefined)).toBeNull()
  })
})

describe('anchorQuote', () => {
  it('joins start and end and drops the Markdown characters', () => {
    expect(
      anchorQuote({
        quoteStart: '## Die **Frist** beginnt mit der [Zustellung](https://x.example/z)',
        quoteEnd: '- und endet `nie`.',
      }),
    ).toBe('Die Frist beginnt mit der Zustellung … und endet nie.')
  })

  it('shows the cells of a table row without raw separators', () => {
    expect(anchorQuote({ quoteStart: 'WF-2026-037;Optik Brandt | 31.01.2027' }, true)).toBe(
      'WF-2026-037 · Optik Brandt · 31.01.2027',
    )
  })

  it('is empty without quotes', () => {
    expect(anchorQuote({ pageFrom: 1 })).toBe('')
    expect(anchorQuote(null)).toBe('')
  })
})
