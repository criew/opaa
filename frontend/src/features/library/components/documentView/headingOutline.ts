import type { OutlineTreeNode } from './OutlineTree'

/** One heading of a rendered document as an outline entry; `index` is its place in reading order. */
export interface HeadingNode extends OutlineTreeNode<HeadingNode> {
  index: number
}

/** A heading as found in the rendered document: its level (1 is outermost) and its title. */
export interface FoundHeading {
  level: number
  title: string
}

/**
 * Nests headings in reading order by their level: a heading belongs under the nearest heading
 * before it with a lower level. Levels only compare, so a skipped level (1 straight to 3) nests
 * one step deeper, and a document opening at level 2 has its level-2 headings at the top.
 */
export function headingTree(headings: FoundHeading[]): HeadingNode[] {
  const roots: HeadingNode[] = []
  const open: { level: number; node: HeadingNode }[] = []
  headings.forEach((heading, index) => {
    const node: HeadingNode = { title: heading.title, index, children: [] }
    while (open.length > 0 && open[open.length - 1].level >= heading.level) open.pop()
    const parent = open[open.length - 1]
    if (parent) parent.node.children.push(node)
    else roots.push(node)
    open.push({ level: heading.level, node })
  })
  return roots
}

/** Every entry of `nodes` and their descendants, in reading order. */
export function flattenHeadings(nodes: HeadingNode[]): HeadingNode[] {
  return nodes.flatMap((node) => [node, ...flattenHeadings(node.children)])
}

const MARKDOWN_HEADINGS = 'h1, h2, h3, h4, h5, h6'

/** The headings (`#` … `######`) of rendered Markdown, in reading order; empty ones are skipped. */
export function markdownHeadingElements(body: ParentNode): HTMLElement[] {
  return Array.from(body.querySelectorAll<HTMLElement>(MARKDOWN_HEADINGS)).filter((element) =>
    headingTitle(element),
  )
}

/** The level of a rendered Markdown heading, from its element name. */
export function markdownHeadingLevel(element: HTMLElement): number {
  return Number(element.tagName.slice(1))
}

/** Class prefix docx-preview carries onto a heading paragraph, followed by its level. */
export const DOCX_HEADING_CLASS = 'opaa-heading-'

/**
 * Word's built-in heading styles, English or localized, by style id - the same rule the indexing
 * applies (`DocxDocumentFormat`); the German „Überschrift 1“ has the id `berschrift1`.
 */
const DOCX_HEADING_STYLE = /^(?:heading|berschrift|ueberschrift)[ _]?(\d)$/i

/** The paragraph model of docx-preview, as far as the outline reads it. */
interface DocxParagraph {
  type?: string
  styleName?: string
  outlineLevel?: number
  className?: string
}

/**
 * Marks the heading paragraphs in a document parsed by docx-preview, before it is rendered, with a
 * class naming their level: a paragraph in a built-in heading style or with an outline level of
 * its own, as the indexing reads it. Word's outline level 9 is body text and stays unmarked.
 */
export function markDocxHeadings(document: unknown): void {
  const body = (document as { documentPart?: { body?: { children?: DocxParagraph[] } } })
    ?.documentPart?.body
  for (const element of body?.children ?? []) {
    if (element?.type !== 'paragraph') continue
    const level = paragraphHeadingLevel(element)
    if (level == null) continue
    element.className = [element.className, `${DOCX_HEADING_CLASS}${level}`]
      .filter(Boolean)
      .join(' ')
  }
}

function paragraphHeadingLevel(paragraph: DocxParagraph): number | null {
  const match = paragraph.styleName ? DOCX_HEADING_STYLE.exec(paragraph.styleName) : null
  if (match) return Number(match[1])
  const outline = paragraph.outlineLevel
  return typeof outline === 'number' && outline >= 0 && outline < 9 ? outline + 1 : null
}

/** The marked heading paragraphs of a rendered Word file, in reading order; empty ones skipped. */
export function docxHeadingElements(body: ParentNode): HTMLElement[] {
  return Array.from(body.querySelectorAll<HTMLElement>(`[class*="${DOCX_HEADING_CLASS}"]`)).filter(
    (element) => docxHeadingLevel(element) != null && headingTitle(element),
  )
}

/** The level a rendered Word heading paragraph was marked with. */
export function docxHeadingLevel(element: HTMLElement): number | null {
  for (const name of Array.from(element.classList)) {
    if (name.startsWith(DOCX_HEADING_CLASS)) {
      const level = Number(name.slice(DOCX_HEADING_CLASS.length))
      if (Number.isInteger(level) && level > 0) return level
    }
  }
  return null
}

/** A heading's text as the outline shows it, whitespace collapsed. */
export function headingTitle(element: HTMLElement): string {
  return (element.textContent ?? '').replace(/\s+/g, ' ').trim()
}
