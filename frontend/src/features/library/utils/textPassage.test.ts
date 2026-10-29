import { describe, expect, it } from 'vitest'
import { comparableText, passageRange } from './textPassage'

function rendered(html: string): HTMLElement {
  const root = document.createElement('div')
  root.innerHTML = html
  return root
}

describe('comparableText', () => {
  it('keeps only letters and digits, lower-cased', () => {
    expect(comparableText('Ein **fetter** `Code`, - Punkt 3.\u00ad Silbe')).toBe(
      'einfettercodepunkt3silbe',
    )
  })
})

describe('passageRange', () => {
  const root = rendered(
    '<h2>Fristen</h2><p>Die Frist beginnt mit der <strong>Zustellung</strong> des Bescheids.</p>' +
      '<p>Sie endet nach <a href="#">zwei Wochen</a>.</p><p>Danach folgt nichts.</p>',
  )

  it('spans the rendered text from the start quote to the end quote', () => {
    const range = passageRange(root, {
      quoteStart: 'Die Frist beginnt mit der **Zustellung**',
      quoteEnd: 'nach [zwei Wochen](https://x.example).',
    })

    expect(range?.toString()).toBe(
      'Die Frist beginnt mit der Zustellung des Bescheids.Sie endet nach zwei Wochen',
    )
  })

  it('marks the start quote alone without an end quote', () => {
    expect(passageRange(root, { quoteStart: 'Danach folgt' })?.toString()).toBe('Danach folgt')
  })

  it('falls back to the head of a start quote whose tail changed', () => {
    const range = passageRange(root, {
      quoteStart: 'Die Frist beginnt mit der Zustellung eines anderen Bescheids',
    })
    expect(range?.toString()).toBe('Die Frist beginnt mit der Zus')
  })

  it('matches a link shown with its address, as for a link target that is not allowed', () => {
    const shown = rendered('<p>Siehe <span>Handbuch (https://x.example/h)</span> dazu.</p>')
    expect(
      passageRange(shown, {
        quoteStart: 'Siehe [Handbuch](https://x.example/h) dazu.',
      })?.toString(),
    ).toBe('Siehe Handbuch (https://x.example/h) dazu.')
  })

  it('matches an ordered list whose numbers the page shows only as list markers', () => {
    const list = rendered('<ol><li>Antrag stellen</li><li>Frist abwarten</li></ol>')
    expect(
      passageRange(list, { quoteStart: '1. Antrag stellen 2. Frist abwarten' })?.toString(),
    ).toBe('Antrag stellenFrist abwarten')
  })

  it('ignores an end quote too far from the start', () => {
    const far = rendered(`<p>Anfang hier.</p><p>${'x '.repeat(25_000)}</p><p>Ende dort.</p>`)
    expect(
      passageRange(far, { quoteStart: 'Anfang hier.', quoteEnd: 'Ende dort.' })?.toString(),
    ).toBe('Anfang hier.')
  })

  it('finds nothing for a quote that is not in the text', () => {
    expect(passageRange(root, { quoteStart: 'Ganz anderer Text' })).toBeNull()
    expect(passageRange(root, null)).toBeNull()
  })
})
