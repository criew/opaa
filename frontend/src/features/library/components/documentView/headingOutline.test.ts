import { describe, expect, it } from 'vitest'
import {
  docxHeadingElements,
  docxHeadingLevel,
  flattenTree,
  headingTree,
  markDocxHeadings,
} from './headingOutline'

describe('headingTree', () => {
  it('nests each heading under the nearest one before it with a lower level', () => {
    const tree = headingTree([
      { level: 1, title: 'A' },
      { level: 2, title: 'A.1' },
      { level: 3, title: 'A.1.a' },
      { level: 2, title: 'A.2' },
      { level: 1, title: 'B' },
    ])
    expect(tree.map((node) => node.title)).toEqual(['A', 'B'])
    expect(tree[0].children.map((node) => node.title)).toEqual(['A.1', 'A.2'])
    expect(tree[0].children[0].children.map((node) => node.title)).toEqual(['A.1.a'])
    expect(flattenTree(tree).map((node) => node.index)).toEqual([0, 1, 2, 3, 4])
  })

  it('keeps a document opening below level 1 at the top and a skipped level one step deeper', () => {
    const tree = headingTree([
      { level: 2, title: 'Vorbemerkung' },
      { level: 1, title: 'Teil 1' },
      { level: 3, title: 'Einzelheit' },
    ])
    expect(tree.map((node) => node.title)).toEqual(['Vorbemerkung', 'Teil 1'])
    expect(tree[1].children.map((node) => node.title)).toEqual(['Einzelheit'])
  })
})

describe('markDocxHeadings', () => {
  it('marks built-in heading styles and own outline levels as the indexing reads them', () => {
    const paragraphs = [
      { type: 'paragraph', styleName: 'berschrift1' },
      { type: 'paragraph', styleName: 'Heading 2' },
      { type: 'paragraph', styleName: 'ueberschrift_3', className: 'docx-cnf' },
      { type: 'paragraph', outlineLevel: 1 },
      { type: 'paragraph', outlineLevel: 9 },
      { type: 'paragraph', styleName: 'Standard' },
      { type: 'table', styleName: 'Heading1' },
    ]
    markDocxHeadings({ documentPart: { body: { children: paragraphs } } })
    expect(paragraphs.map((p) => ('className' in p ? p.className : undefined))).toEqual([
      'opaa-heading-1',
      'opaa-heading-2',
      'docx-cnf opaa-heading-3',
      'opaa-heading-2',
      undefined,
      undefined,
      undefined,
    ])
  })

  it('tolerates a document without a body', () => {
    expect(() => markDocxHeadings({})).not.toThrow()
    expect(() => markDocxHeadings(null)).not.toThrow()
  })

  it('finds the marked paragraphs with text, in reading order', () => {
    const body = document.createElement('div')
    body.innerHTML =
      '<p class="docx_berschrift1 opaa-heading-1">Eins</p><p>Text</p>' +
      '<p class="opaa-heading-2">  </p><p class="docx-cnf opaa-heading-2">Zwei</p>'
    const found = docxHeadingElements(body)
    expect(found.map((element) => element.textContent)).toEqual(['Eins', 'Zwei'])
    expect(found.map(docxHeadingLevel)).toEqual([1, 2])
  })
})
