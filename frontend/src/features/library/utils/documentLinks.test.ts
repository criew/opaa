import { describe, expect, it } from 'vitest'
import { neutralizeLinks } from './documentLinks'

function rendered(html: string): HTMLElement {
  const root = document.createElement('div')
  root.innerHTML = html
  return root
}

describe('neutralizeLinks', () => {
  it('keeps an http(s) target and opens it apart from the app', () => {
    const root = rendered('<a href="https://stadt.example/foerderung" target="_self">Förderung</a>')
    neutralizeLinks(root)
    const link = root.querySelector('a')!
    expect(link.getAttribute('href')).toBe('https://stadt.example/foerderung')
    expect(link.getAttribute('target')).toBe('_blank')
    expect(link.getAttribute('rel')).toBe('noopener noreferrer')
  })

  it.each([
    'javascript:alert(1)',
    ' JaVaScRiPt:alert(1)',
    'java\tscript:alert(1)',
    'data:text/html,<script>alert(1)</script>',
    'vbscript:msgbox(1)',
    'file:///etc/passwd',
    '#_Toc123',
    '/relative/path',
    '',
  ])('removes the target %j and leaves the text', (href) => {
    const root = rendered(`<a href="${href}" ping="https://track.example" download>Text</a>`)
    neutralizeLinks(root)
    const link = root.querySelector('a')!
    expect(link.hasAttribute('href')).toBe(false)
    expect(link.hasAttribute('ping')).toBe(false)
    expect(link.hasAttribute('download')).toBe(false)
    expect(link.textContent).toBe('Text')
  })

  it('removes an SVG link target as well', () => {
    const root = rendered(
      '<svg><a xlink:href="javascript:alert(1)" href="javascript:alert(1)"><text>x</text></a></svg>',
    )
    neutralizeLinks(root)
    const link = root.querySelector('a')!
    expect(link.getAttribute('href')).toBeNull()
    expect(link.getAttributeNS('http://www.w3.org/1999/xlink', 'href')).toBeNull()
  })
})
