import { renderAsync } from 'docx-preview'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { storedZip } from '../mocks/storedZip'
import { confineRenderedDocx } from './docxConfinement'

const W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'

/**
 * A Word file whose paragraph style pastes a colour that closes its rule and opens one against
 * the host, and whose VML shape asks to cover the whole window.
 */
function craftedDocx(): Blob {
  const zip = storedZip({
    '[Content_Types].xml':
      '<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">' +
      '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>' +
      '<Default Extension="xml" ContentType="application/xml"/>' +
      '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>' +
      '<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>',
    '_rels/.rels':
      '<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
      '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>',
    'word/_rels/document.xml.rels':
      '<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
      '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>',
    'word/styles.xml':
      `<?xml version="1.0" encoding="UTF-8"?><w:styles xmlns:w="${W}">` +
      '<w:style w:type="paragraph" w:styleId="Boese"><w:name w:val="Boese"/><w:rPr>' +
      '<w:color w:val="000}:host{display:none!important}.docx b{position:fixed;inset:0"/>' +
      '</w:rPr></w:style></w:styles>',
    'word/document.xml':
      `<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="${W}" xmlns:v="urn:schemas-microsoft-com:vml"><w:body>` +
      '<w:p><w:pPr><w:pStyle w:val="Boese"/></w:pPr><w:r><w:t>Frist</w:t></w:r></w:p>' +
      '<w:p><w:r><w:pict><v:rect style="position:fixed;inset:0;z-index:2147483647"/></w:pict></w:r></w:p>' +
      '</w:body></w:document>',
  })
  return new Blob([zip])
}

afterEach(() => {
  vi.unstubAllGlobals()
  document.body.replaceChildren()
})

function rendered(html: string): HTMLElement {
  // jsdom builds no stylesheet for a style element in a shadow root, browsers do; the rules are
  // the same either way
  const root = document.createElement('div')
  document.body.append(root)
  root.innerHTML = html
  return root
}

function rules(root: HTMLElement): string[] {
  return Array.from(root.querySelector('style')!.sheet!.cssRules).map(
    (rule) => (rule as CSSStyleRule).selectorText,
  )
}

describe('confineRenderedDocx', () => {
  it('drops rules a crafted colour opened against the host and anything but style rules', () => {
    const root = rendered(
      `<style>.docx p { color: #000; } :host { display: none !important; } ` +
        `@import url("https://example.org/x.css"); ` +
        `.docx span { color: red; } :root { color: red; }</style>`,
    )

    confineRenderedDocx(root)

    expect(rules(root)).toEqual(['.docx p', '.docx span'])
  })

  it('takes fixed and sticky positions from rules and inline styles, VML shapes included', () => {
    const root = rendered(
      `<style>.docx svg { position: fixed; inset: 0; z-index: 2147483647; } ` +
        `.docx b { position: sticky; top: 0; } .docx i { position: relative; }</style>` +
        `<svg style="position:fixed;inset:0;z-index:2147483647"></svg>` +
        `<p style="position: relative; color: red">Text</p>`,
    )

    confineRenderedDocx(root)

    const [svgRule, bRule, iRule] = Array.from(root.querySelector('style')!.sheet!.cssRules).map(
      (rule) => (rule as CSSStyleRule).style,
    )
    expect(svgRule.position).toBe('')
    expect(svgRule.zIndex).toBe('2147483647')
    expect(bRule.position).toBe('')
    expect(iRule.position).toBe('relative')
    expect(root.querySelector<SVGElement>('svg')!.style.position).toBe('')
    expect(root.querySelector<HTMLElement>('p')!.style.position).toBe('relative')
  })

  it('confines what docx-preview renders from a crafted Word file', async () => {
    // jsdom lays out no SVG; docx-preview sizes VML shapes a frame after rendering
    vi.stubGlobal('requestAnimationFrame', () => 0)
    const styles = document.createElement('div')
    const body = document.createElement('div')
    document.body.append(styles, body)
    await renderAsync(craftedDocx(), body, styles, { ignoreFonts: true, experimental: false })
    const css = Array.from(styles.querySelectorAll('style'), (style) => style.textContent).join()
    expect(css).toContain(':host{display:none!important}')
    expect(body.querySelector<SVGElement>('svg')!.style.position).toBe('fixed')

    confineRenderedDocx(styles)
    confineRenderedDocx(body)

    const left = Array.from(styles.querySelectorAll('style')).flatMap(
      (style) => Array.from(style.sheet!.cssRules) as CSSStyleRule[],
    )
    expect(left.some((rule) => /:host/.test(rule.selectorText))).toBe(false)
    expect(left.some((rule) => rule.style.position === 'fixed')).toBe(false)
    expect(body.querySelector<SVGElement>('svg')!.style.position).toBe('')
    expect(body.textContent).toContain('Frist')
  })
})
