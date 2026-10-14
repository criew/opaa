import type { SxProps, Theme } from '@mui/material/styles'
import { fontFamily } from '@/shared/theme/tokens'

/**
 * The table language of the model area: column heads as letter-spaced capitals in the
 * monospace font, cells aligned to the top and clipped instead of wrapping.
 *
 * In one place, so that the area's two tables do not drift apart — whoever changes a
 * row height changes it for both.
 */
export const MODEL_TABLE_SX: SxProps<Theme> = {
  tableLayout: 'fixed',
  '& th': { fontFamily: fontFamily.mono, fontSize: 10, letterSpacing: '0.08em' },
  '& td': { fontSize: 13, py: 1.25, verticalAlign: 'top', overflow: 'hidden' },
}

/** Machine values - identifiers, addresses, numbers - are set in the monospace font. */
export const MACHINE_VALUE_SX: SxProps<Theme> = {
  fontFamily: fontFamily.mono,
  fontSize: 12,
  overflowWrap: 'anywhere',
}
