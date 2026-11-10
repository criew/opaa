import { describe, expect, it } from 'vitest'
import { answerForOutside } from './outwardMarkdown'

const PAGE = 'https://opaa.example/chat'

describe('answerForOutside', () => {
  it('writes an allowed relative or scheme-only link out against the page', () => {
    expect(answerForOutside('[a](/api/docs/1) [b](https:x/y)', { pageHref: PAGE })).toBe(
      '[a](https://opaa.example/api/docs/1) [b](https://opaa.example/x/y)',
    )
  })

  it('writes an allowed reference link inline and drops the definition', () => {
    const output = answerForOutside('[Amt][a]\n\n[a]: https://amt.example/s', {
      allowedOrigins: ['https://amt.example'],
      pageHref: PAGE,
    })
    expect(output.trim()).toBe('[Amt](https://amt.example/s)')
  })

  it('keeps an address with backticks whole in its code span', () => {
    expect(answerForOutside('![b](https://evil.example/`x`)', { pageHref: PAGE })).toBe(
      '[Bild: b] (`` https://evil.example/`x` ``)',
    )
  })

  it('shows a bare foreign address as text only once', () => {
    expect(answerForOutside('Siehe <https://evil.example/a>.', { pageHref: PAGE })).toBe(
      'Siehe `https://evil.example/a`.',
    )
  })

  it('gives the same text when applied twice', () => {
    const once = answerForOutside(
      '![x](https://evil.example/p.png) [y](https://evil.example/a) <b>c</b> [z](/d)',
      { pageHref: PAGE },
    )
    expect(answerForOutside(once, { pageHref: PAGE })).toBe(once)
  })
})
