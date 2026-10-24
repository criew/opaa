/** The one content width of wizard, review, editing and the Reiter „Quelle“. */
export const CONTENT_WIDTH = 720

/** The grid of every summary row, the review step and the detail tab: title | content | link. */
export const ROW_GRID = {
  display: 'grid',
  gridTemplateColumns: { xs: 'minmax(0, 1fr) auto', sm: '128px minmax(0, 1fr) auto' },
  columnGap: 2,
  rowGap: 0.5,
  alignItems: 'baseline',
} as const

/** A text link: no padding, no tint, underline on hover - at the content edge like running text. */
export const TEXT_LINK = {
  fontSize: 13.5,
  fontWeight: 500,
  textAlign: 'left',
  verticalAlign: 'baseline',
  minHeight: 24,
  minWidth: 24,
} as const
