import { fontFamily, fontSize } from '../../theme/tokens'

/**
 * Fixed widths, because the content is not: addresses and names are arbitrarily long. A cell
 * stays on one line and cuts off instead of wrapping or running into its neighbour; a tooltip
 * names the value (guidelines 5.3).
 */
export const adminTableSx = {
  tableLayout: 'fixed',
  '& th': {
    fontFamily: fontFamily.mono,
    fontSize: fontSize.label,
    letterSpacing: '0.08em',
    whiteSpace: 'nowrap',
  },
  '& td': {
    fontSize: fontSize.text,
    py: 1.25,
    verticalAlign: 'top',
    overflow: 'hidden',
    whiteSpace: 'nowrap',
    textOverflow: 'ellipsis',
  },
  // Selected through the table like the rule above, so it outweighs it.
  '& td[data-wrap]': { whiteSpace: 'normal', overflowWrap: 'anywhere' },
} as const

/** One row of the narrow layout: a list entry, not a card - the same language as the table. */
export const listCardSx = {
  borderBottom: 1,
  borderColor: 'divider',
  py: 1.5,
  '&:last-of-type': { borderBottom: 0 },
} as const

/** The facts of one entry in the narrow layout, as term and value side by side. */
export const definitionListSx = {
  display: 'grid',
  gridTemplateColumns: 'max-content 1fr',
  columnGap: 1.5,
  rowGap: 0.75,
  m: 0,
  mt: 1.25,
  '& dt': { fontSize: fontSize.meta, color: 'text.secondary', pt: '1px' },
  '& dd': { m: 0, fontSize: fontSize.text, minWidth: 0, overflowWrap: 'anywhere' },
} as const
