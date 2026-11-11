/** The grid of every summary row, the review step and the detail tab: title | content | link. */
export const REVIEW_ROW_GRID = {
  display: 'grid',
  gridTemplateColumns: { xs: 'minmax(0, 1fr) auto', sm: '128px minmax(0, 1fr) auto' },
  columnGap: 2,
  rowGap: 0.5,
  alignItems: 'baseline',
} as const
