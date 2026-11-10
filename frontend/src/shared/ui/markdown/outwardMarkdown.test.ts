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

  it('shows a bare e-mail, www. or https address once and leaves code alone', () => {
    const code = '```js\nif (a < b && c[0]) { x = "<img src=x>" }\n```'
    const answer = [
      'Kontakt: buergerbuero@stadt.example, www.stadt.example und https://stadt.example/a.',
      '',
      code,
      '',
      'Im Text `a[0] < b` bleibt.',
    ].join('\n')

    const output = answerForOutside(answer, { pageHref: PAGE })

    expect(output).toBe(
      [
        'Kontakt: `buergerbuero@stadt.example`, `www.stadt.example` und' +
          ' `https://stadt.example/a`.',
        '',
        code,
        '',
        'Im Text `a[0] < b` bleibt.',
      ].join('\n'),
    )
  })

  it('lets no bracket around a rewritten link close a link the answer never had', () => {
    const output = answerForOutside('[[b](https://evil.example/x)](https://amt.example/y)', {
      allowedOrigins: ['https://amt.example'],
      pageHref: PAGE,
    })

    expect(output).toBe('\\[b (`https://evil.example/x`)\\](https://amt.example/y)')
  })

  it.each([
    '![x](https://evil.example/p.png) [y](https://evil.example/a) <b>c</b> [z](/d)',
    'Mail an a@b.example oder www.b.example, siehe <https://b.example/x>.',
    '[mail a@b.example](https://evil.example/m) und [www.b.example](https://evil.example/w)',
    '- [[b](https://evil.example/x)](https://amt.example/y)\n- [r][r]\n\n[r]: https://evil.example',
    '| a | b |\n|---|---|\n| ![i](https://evil.example/i) | <span>x</span> |',
    '<div>\n![z](https://evil.example/block.png)\n</div>\n\n```\n<img src=x>\n```',
    '![alt mit ![inner](https://evil.example/in) und a@b.example](https://evil.example/o)',
  ])('gives the same text when applied twice: %s', (answer) => {
    const policy = { allowedOrigins: ['https://amt.example'], pageHref: PAGE }
    const once = answerForOutside(answer, policy)

    expect(answerForOutside(once, policy)).toBe(once)
    expect(once).not.toMatch(/\\\\\\/)
  })
})
