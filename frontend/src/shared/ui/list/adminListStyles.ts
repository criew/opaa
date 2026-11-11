import { fontFamily, fontSize } from '../../theme/tokens'

/**
 * Fixed widths, because the content is not: addresses and names are arbitrarily long. A cell
 * stays on one line and cuts off instead of wrapping or running into its neighbour; a tooltip
 * names the value (guidelines 5.3). A head too wide for its column ends in „…“ as well; the
 * column widths are chosen so that this does not happen at 1280 px.
 */
export const adminTableSx = {
  tableLayout: 'fixed',
  '& th': {
    fontFamily: fontFamily.mono,
    fontSize: fontSize.label,
    letterSpacing: '0.08em',
    whiteSpace: 'nowrap',
    overflow: 'hidden',
    textOverflow: 'ellipsis',
  },
  '& td': {
    fontSize: fontSize.text,
    py: 1.25,
    verticalAlign: 'top',
    overflow: 'hidden',
    whiteSpace: 'nowrap',
    textOverflow: 'ellipsis',
  },
  // Selected through the table like the rule above, so they outweigh it.
  '& td[data-wrap]': { whiteSpace: 'normal', overflowWrap: 'anywhere' },
  '& td[data-row-detail-cell]': { whiteSpace: 'normal', overflowWrap: 'anywhere', paddingTop: 0 },
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
