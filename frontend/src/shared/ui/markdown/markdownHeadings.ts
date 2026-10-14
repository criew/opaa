// Minimal structural types for the hast tree react-markdown's rehype pipeline hands us - the
// 'hast' package itself is only a transitive dependency, which pnpm's strict node_modules
// deliberately keeps un-importable. Only the fields this plugin touches are modelled.
interface HastNode {
  type: string
  tagName?: string
  properties?: Record<string, unknown>
  children?: HastNode[]
}

type Element = HastNode & { tagName: string }
type Root = HastNode

const HEADING_TAGS = ['h1', 'h2', 'h3', 'h4', 'h5', 'h6'] as const

/** Property the plugin stamps so the renderer can keep the visual variant of the original level. */
export const MD_LEVEL_PROPERTY = 'dataMdLevel'

function isHeading(node: HastNode): node is Element {
  return (
    node.type === 'element' &&
    node.tagName !== undefined &&
    (HEADING_TAGS as readonly string[]).includes(node.tagName)
  )
}

function walk(node: Root | Element, visitor: (heading: Element) => void): void {
  for (const child of node.children ?? []) {
    if (isHeading(child)) {
      visitor(child)
    }
    if (child.children) {
      walk(child, visitor)
    }
  }
}

/**
 * Normalises the heading levels of one rendered message (#1016): an assistant answer may start at
 * any Markdown level (### as the first heading is common), but the chat page already carries the
 * h1 - unchanged levels would land as h5/h6 right after it (axe heading-order, the same class
 * #958/#1015 fixed for static headings).
 *
 * Rank compression, not a fixed offset: the distinct levels that actually occur are mapped, in
 * order, onto h2, h3, ... - so "#" followed by "###" becomes h2/h3 rather than h2/h4, which would
 * just be a new skipped level. The original level survives as {@link MD_LEVEL_PROPERTY} so the
 * renderer keeps the visual gradation of the source Markdown untouched.
 */
export default function rehypeNormalizeHeadings() {
  // Typed loosely on purpose: react-markdown's plugin signature expects unified's own types,
  // which live in the same un-importable transitive layer as 'hast'.
  return (tree: Root) => {
    const levels = new Set<number>()
    walk(tree, (heading) => {
      levels.add(Number(heading.tagName.slice(1)))
    })
    if (levels.size === 0) {
      return
    }
    const rankByLevel = new Map([...levels].sort((a, b) => a - b).map((level, i) => [level, i]))
    walk(tree, (heading) => {
      const level = Number(heading.tagName.slice(1))
      const normalized = Math.min(2 + (rankByLevel.get(level) ?? 0), 6)
      heading.properties = { ...heading.properties, [MD_LEVEL_PROPERTY]: String(level) }
      heading.tagName = `h${normalized}`
    })
  }
}
