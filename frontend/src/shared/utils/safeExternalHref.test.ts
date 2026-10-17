import { describe, expect, it } from 'vitest'
import { safeExternalHref } from './safeExternalHref'

// Addresses from indexed third-party sources end up as link targets; only http(s) may pass.
describe('safeExternalHref', () => {
  it('passes an https address', () => {
    expect(safeExternalHref('https://example.gov/verzeichnis/doc.pdf')).toBe(
      'https://example.gov/verzeichnis/doc.pdf',
    )
  })

  it('passes an http address', () => {
    expect(safeExternalHref('http://wiki.example/pages/viewpage.action?pageId=42')).toBe(
      'http://wiki.example/pages/viewpage.action?pageId=42',
    )
  })

  it('passes an upper-case scheme, normalised', () => {
    expect(safeExternalHref('HTTPS://example.gov/a')).toBe('https://example.gov/a')
    expect(safeExternalHref('HTTP://example.gov/a')).toBe('http://example.gov/a')
  })

  it('passes an https address with leading whitespace, normalised', () => {
    expect(safeExternalHref('  https://example.gov/a')).toBe('https://example.gov/a')
    expect(safeExternalHref('\thttps://example.gov/a')).toBe('https://example.gov/a')
  })

  it.each([
    'javascript:alert(1)',
    'JaVaScRiPt:alert(1)',
    'data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==',
    'DATA:text/html,<script>alert(1)</script>',
    'vbscript:msgbox(1)',
    'file:///etc/passwd',
    'mailto:a@example.gov',
    'ftp://example.gov/a',
    ' javascript:alert(1)',
    '\tjavascript:alert(1)',
    '\u0000javascript:alert(1)',
    '\u001fjavascript:alert(1)',
    'java\tscript:alert(1)',
    'java\nscript:alert(1)',
  ])('rejects the non-http(s) address %j', (url) => {
    expect(safeExternalHref(url)).toBeUndefined()
  })

  it.each(['/documents/1', 'documents/1', '//evil.example/a', '#anker', '?q=1', 'https//x'])(
    'rejects the relative address %j',
    (url) => {
      expect(safeExternalHref(url)).toBeUndefined()
    },
  )

  it.each(['', '   ', 'https://', 'http://[', null, undefined])(
    'rejects the empty or unparsable value %j',
    (url) => {
      expect(safeExternalHref(url)).toBeUndefined()
    },
  )
})
