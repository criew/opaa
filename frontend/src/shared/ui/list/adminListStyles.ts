import { fontFamily } from '../../theme/tokens'

/**
 * Fixed widths, because the content is not: addresses and names are arbitrarily long. With
 * `overflow` a cell cuts off instead of running into its neighbour, and a tooltip names the value.
 */
export const adminTableSx = {
  tableLayout: 'fixed',
  '& th': { fontFamily: fontFamily.mono, fontSize: 10, letterSpacing: '0.08em' },
  '& td': { fontSize: 13, py: 1.25, verticalAlign: 'top', overflow: 'hidden' },
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
  '& dt': { fontSize: 12, color: 'text.secondary', pt: '1px' },
  '& dd': { m: 0, fontSize: 13, minWidth: 0, overflowWrap: 'anywhere' },
} as const
