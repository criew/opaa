import { fontSize } from '@/shared/theme/tokens' /** The one content width of wizard, review, editing and the Reiter „Quelle“. */
export const CONTENT_WIDTH = 720

/** The grid of every summary row, the review step and the detail tab: title | content | link. */
export { REVIEW_ROW_GRID as ROW_GRID } from '@/shared/ui/wizard/reviewRowGrid'

/** A text link: no padding, no tint, underline on hover - at the content edge like running text. */
export const TEXT_LINK = {
  fontSize: fontSize.text,
  fontWeight: 500,
  textAlign: 'left',
  verticalAlign: 'baseline',
  minHeight: 24,
  minWidth: 24,
} as const
