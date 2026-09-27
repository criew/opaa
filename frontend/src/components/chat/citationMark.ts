import type { Theme } from '@mui/material/styles'
import { alpha } from '@mui/material/styles'
import { fontFamily, radius } from '../../theme/tokens'

/** Accent tint of a resting mark - stronger in the dark scheme, where 10 % would vanish. */
export function citationMarkTint(theme: Theme): string {
  return alpha(theme.palette.primary.main, theme.palette.mode === 'dark' ? 0.22 : 0.1)
}

/**
 * The footnote mark: a digit (or range, "+n") in a tinted accent circle that widens to a pill.
 * One look for the marks in the answer text and in EvidenceFooter's "Belege anzeigen", so the
 * reader sees they point at the same Belege. The tint sits on a solid backdrop, so a mark
 * overlapping its neighbour covers it instead of blending into it.
 */
export function citationMarkSx(theme: Theme, size: number, fontSize = Math.round(size * 0.5)) {
  const tint = citationMarkTint(theme)
  return {
    display: 'inline-grid',
    placeItems: 'center',
    minWidth: size,
    height: size,
    px: '5px',
    borderRadius: `${radius.pill}px`,
    fontFamily: fontFamily.mono,
    fontSize,
    fontWeight: 600,
    lineHeight: 1,
    fontVariantNumeric: 'tabular-nums',
    color: theme.palette.primary.main,
    background: `linear-gradient(${tint}, ${tint}), ${theme.palette.background.default}`,
  } as const
}
