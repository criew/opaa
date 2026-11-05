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
    ['control characters in a string', `'a\u0000b\u0001c\u000bd\u001fe\u007f'`],
    ['an identity escape of a control character', `'\\\u0002'`],
    ['a control character in a regular expression', `/\u0003/.test('x\u0003')`],
  ])('keeps the meaning of %s and leaves only ASCII', (_, source) => {
    const escaped = escapeNonAsciiJs(source)
    expect(escaped).not.toMatch(/[^\t\n\r\x20-\x7e]/)
    expect(evaluate(escaped)).toEqual(evaluate(source))
  })
})

describe('prototypeOutput', () => {
  it('escapes an emitted script asset, not only the chunks', () => {
    const bundle = {
      worker: { type: 'asset', fileName: 'pdf.worker.min.js', source: 'var a="\u0001ü";' },
      style: { type: 'asset', fileName: 'index.css', source: 'a{content:"ü"}' },
      chunk: { type: 'chunk', fileName: 'index.js', code: 'var b="ä";' },
    }
    const generate = prototypeOutput().generateBundle as (opts: unknown, b: unknown) => void
    generate({}, bundle)
    expect(bundle.worker.source).toBe('var a="\\u0001\\u00fc";')
    expect(bundle.style.source).toBe('a{content:"ü"}')
    expect(bundle.chunk.code).toBe('var b="\\u00e4";')
  })

  it('replaces the page title', () => {
    const transform = prototypeOutput().transformIndexHtml as (html: string) => string
    expect(transform('<head><title>OPAA</title></head>')).toBe(
      `<head><title>${PROTOTYPE_TITLE}</title></head>`,
    )
    expect(PROTOTYPE_TITLE).toBe('OPAA-Prototyp')
  })
})
