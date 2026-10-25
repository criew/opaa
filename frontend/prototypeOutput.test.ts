import { describe, expect, it } from 'vitest'
import { escapeNonAsciiJs, PROTOTYPE_TITLE, prototypeOutput } from './prototypeOutput'

// Evaluates a JavaScript expression the way the browser would read the escaped chunk.
const evaluate = (code: string): unknown => new Function(`return ${code}`)()

describe('escapeNonAsciiJs', () => {
  it.each([
    ['a string', `'Grundstücksverkehrsgenehmigungsakte – „Ä“ \uFFFD'`],
    ['a template', '`Größe ${1 + 1} €`'],
    ['an astral character', `'Ablage 📁'`],
    ['an identity escape', `'\\ä'`],
    ['an escaped backslash before a character', `'\\\\ä'`],
    ['a regular expression', `/[äöü]+/.test('Bürger')`],
    ['a unicode regular expression', `/^📁$/u.test('📁')`],
  ])('keeps the meaning of %s and leaves only ASCII', (_, source) => {
    const escaped = escapeNonAsciiJs(source)
    expect(escaped).not.toMatch(/[^\t\n\r\x20-\x7e]/)
    expect(evaluate(escaped)).toEqual(evaluate(source))
  })
})

describe('prototypeOutput', () => {
  it('replaces the page title', () => {
    const transform = prototypeOutput().transformIndexHtml as (html: string) => string
    expect(transform('<head><title>OPAA</title></head>')).toBe(
      `<head><title>${PROTOTYPE_TITLE}</title></head>`,
    )
    expect(PROTOTYPE_TITLE).toBe('OPAA-Prototyp')
  })
})
