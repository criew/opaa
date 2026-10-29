import { describe, expect, it } from 'vitest'
import { comparableText, passageRange } from './textPassage'

function rendered(html: string): HTMLElement {
  const root = document.createElement('div')
  root.innerHTML = html
  return root
}

describe('comparableText', () => {
  it('drops whitespace, Markdown markup and link targets and lower-cases', () => {
    expect(comparableText('Ein **fetter** [Link](https://x.example/a) `Code`')).toBe(
      'einfetterlinkcode',
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
      'Die Frist beginnt mit der Zustellung des Bescheids.Sie endet nach zwei Wochen.',
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

  it('finds nothing for a quote that is not in the text', () => {
    expect(passageRange(root, { quoteStart: 'Ganz anderer Text' })).toBeNull()
    expect(passageRange(root, null)).toBeNull()
  })
})
