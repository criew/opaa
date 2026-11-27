import { fontSize, fontWeight, radius } from '@/shared/theme/tokens'

/** The label „Fundstelle“ as the text views draw it; the PDF view draws the same in its pages. */
export const passageLabelSx = {
  position: 'absolute',
  zIndex: 2,
  pointerEvents: 'none',
  whiteSpace: 'nowrap',
  bgcolor: 'documentView.edge',
  color: 'documentView.edgeFg',
  fontSize: fontSize.label,
  fontWeight: fontWeight.semibold,
  lineHeight: '16px',
  px: 1,
  borderRadius: `${radius.xs}px`,
  '@media (forced-colors: active)': {
    backgroundColor: 'Highlight',
    color: 'HighlightText',
    forcedColorAdjust: 'none',
  },
} as const

/** The scrolling desk around the paper. */
export const deskScrollSx = {
  flex: 1,
  minHeight: 0,
  minWidth: 0,
  overflow: 'auto',
  px: { xs: 0, sm: 3 },
  pt: { xs: 0, sm: 1 },
  pb: { xs: 0, sm: 4 },
} as const
