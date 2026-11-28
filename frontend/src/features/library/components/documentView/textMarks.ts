import type { Theme } from '@mui/material/styles'

/** Attribute on every block the cited passage runs through; it carries the edge stroke. */
export const PASSAGE_BLOCK_ATTR = 'data-passage'

/** Attribute on the first of those blocks; on a phone it makes room for the label above it. */
export const PASSAGE_FIRST_ATTR = 'data-passage-first'

const BLOCKS = 'p, li, pre, td, th, tr, blockquote, h1, h2, h3, h4, h5, h6'

/**
 * Flags the innermost blocks `range` runs through and returns the first, above which the label
 * „Fundstelle“ stands. Earlier flags below `root` are removed first.
 */
export function markPassageBlocks(root: ParentNode, range: Range): HTMLElement | null {
  root
    .querySelectorAll(`[${PASSAGE_BLOCK_ATTR}]`)
    .forEach((element) => element.removeAttribute(PASSAGE_BLOCK_ATTR))
  const touched = Array.from(root.querySelectorAll<HTMLElement>(BLOCKS)).filter((block) =>
    intersects(range, block),
  )
  const innermost = touched.filter(
    (block) => !touched.some((other) => other !== block && block.contains(other)),
  )
  root
    .querySelectorAll(`[${PASSAGE_FIRST_ATTR}]`)
    .forEach((element) => element.removeAttribute(PASSAGE_FIRST_ATTR))
  for (const block of innermost) block.setAttribute(PASSAGE_BLOCK_ATTR, '')
  innermost[0]?.setAttribute(PASSAGE_FIRST_ATTR, '')
  return innermost[0] ?? null
}

function intersects(range: Range, node: Node): boolean {
  try {
    return range.intersectsNode(node)
  } catch {
    return false
  }
}

/** The band of the CSS Custom Highlight named `name`, on the light paper only. */
export function highlightStyles(name: string, theme: Theme) {
  return {
    [`::highlight(${name})`]: { backgroundColor: theme.palette.documentView.band },
    '@media (forced-colors: active)': {
      [`::highlight(${name})`]: { backgroundColor: 'Mark', color: 'MarkText' },
    },
  }
}

/** The edge stroke left of each flagged block, the same as beside a Beleg (guidelines 5.8). */
export const passageBlockSx = {
  [`& [${PASSAGE_BLOCK_ATTR}]`]: {
    position: 'relative',
    scrollMarginTop: 48,
    '&::before': {
      content: '""',
      position: 'absolute',
      left: -12,
      top: 0,
      bottom: 0,
      width: 4,
      borderRadius: 2,
      bgcolor: 'documentView.edge',
    },
    '@media (forced-colors: active)': {
      '&::before': { backgroundColor: 'Highlight' },
    },
  },
  '@media (max-width: 599.95px)': { [`& [${PASSAGE_FIRST_ATTR}]`]: { mt: 3 } },
} as const

/** The cited passage as a block of its own (a Textfassung passage, a table row's cells). */
export const passageBandSx = {
  bgcolor: 'documentView.band',
  boxShadow: (theme: Theme) => `inset 4px 0 0 ${theme.palette.documentView.edge}`,
  '@media (forced-colors: active)': {
    backgroundColor: 'Mark',
    color: 'MarkText',
    boxShadow: 'inset 4px 0 0 Highlight',
  },
} as const
