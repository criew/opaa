import { describe, expect, test } from 'vitest'
import { answerAsHtml } from './copyHtml'

const PAGE = 'https://opaa.example/chat'
const NOTE = 'Hinweis. – OPAA, 05.03.2026'

describe('answerAsHtml', () => {
  test('renders the Markdown and ends with the note in a visible footer', () => {
    const html = answerAsHtml('Er kostet **42,60 Euro**.', NOTE, [], PAGE)

    expect(html).toContain('<strong>42,60 Euro</strong>')
    expect(html).toContain(`<hr/><p><em>${NOTE}</em></p>`)
  })

  test('never carries an image along, only its description and address as text', () => {
    const html = answerAsHtml('![x](https://evil.example/p.png?d=geheim) Text', NOTE, [], PAGE)

    expect(html).not.toContain('<img')
    expect(html).not.toContain('<link')
    expect(html).toContain('[Bild: x] (https://evil.example/p.png?d=geheim)')
  })

  test('links only the own origin and allowed targets, other addresses stay text', () => {
    const markdown =
      '[eigen](https://opaa.example/doc) [erlaubt](https://amt.example/a) [fremd](https://evil.example/a) [mail](mailto:a@b.example)'

    const html = answerAsHtml(markdown, NOTE, ['https://amt.example'], PAGE)

    expect(html).toContain('<a href="https://opaa.example/doc">eigen</a>')
    expect(html).toContain('<a href="https://amt.example/a">erlaubt</a>')
    expect(html).not.toContain('href="https://evil.example')
    expect(html).not.toContain('href="mailto:')
    expect(html).toContain('fremd (https://evil.example/a)')
    expect(html).toContain('mail (mailto:a@b.example)')
  })

  test('puts the resolved address of an allowed link into the HTML, never a scheme-only target', () => {
    const html = answerAsHtml('[a](https:evil.example/x) [b](/api/docs/1)', NOTE, [], PAGE)

    expect(html).not.toContain('href="https:evil')
    expect(html).not.toContain('href="/api')
    expect(html).toContain('<a href="https://opaa.example/evil.example/x">a</a>')
    expect(html).toContain('<a href="https://opaa.example/api/docs/1">b</a>')
  })

  test('resolves single-slash and reference-style scheme-only targets as well', () => {
    const html = answerAsHtml(
      '[a](https:/evil.example/y) [b][r]\n\n[r]: https:evil.example/r',
      NOTE,
      [],
      PAGE,
    )

    expect(html).not.toMatch(/href="https:(?!\/\/opaa\.example\/)/)
    expect(html).toContain('href="https://opaa.example/evil.example/y"')
    expect(html).toContain('href="https://opaa.example/evil.example/r"')
  })

  test('shows footnotes with a German visible heading and without jump links', () => {
    const html = answerAsHtml('Kostet 42 Euro[^1].\n\n[^1]: 001_personalausweis.md', NOTE, [], PAGE)

    expect(html).toContain('Quellen')
    expect(html).toContain('001_personalausweis.md')
    expect(html).not.toContain('Footnotes')
    expect(html).not.toContain('sr-only')
    expect(html).not.toContain('Back to reference')
    expect(html).not.toContain('href=')
    expect(html).not.toContain('↩')
  })

  test('escapes the note', () => {
    expect(answerAsHtml('Text', '<script>x</script>', [], PAGE)).not.toContain('<script>')
  })
})
