import type { Theme } from '@mui/material/styles'
import { rgbToHex } from '@mui/material/styles'
import { compositeOver, deriveAccentText } from '../../utils/contrast'
import { fontFamily, radius } from '../../theme/tokens'

/** Accent share of a resting mark's tint - stronger in the dark scheme, where 10 % would vanish. */
const TINT_ALPHA = { light: 0.1, dark: 0.22 } as const

export interface CitationMarkColors {
  /** The resting mark's opaque tint: the accent composited onto the page ground. */
  tint: string
  /** The resting digit - the accent, moved just far enough to reach 4.5:1 on {@link tint}. */
  text: string
  /** Hover/tooltip state: the pressed accent surface, which carries white at >= 4.5:1. */
  filledSurface: string
  filledText: string
}

/**
 * The mark's colours for one theme. The digit is small text, so both states hold 4.5:1: the
 * accent text role is only guaranteed on the plain grounds, not on its own tint, and the accent
 * as a surface carries white at 3.3:1 in the dark scheme - hence the derived text colour and the
 * pressed surface.
 */
export function citationMarkColors(theme: Theme): CitationMarkColors {
  const ground = theme.palette.background.default
  const accent = theme.palette.primary.main
  const tint = compositeOver(accent, ground, TINT_ALPHA[theme.palette.mode]) ?? ground
  return {
    tint,
    text: deriveAccentText(accent, [tint]),
    // A house colour's pressed surface comes from MUI's darken() as rgb(); contrast math wants hex.
    filledSurface: rgbToHex(theme.palette.primary.dark),
    filledText: theme.palette.primary.contrastText,
  }
}

/**
 * The footnote mark: a digit (or range, "+n") in a tinted accent circle that widens to a pill.
 * One look for the marks in the answer text and in EvidenceFooter's "Belege anzeigen", so the
 * reader sees they point at the same Belege. The tint is opaque, so a mark overlapping its
 * neighbour covers it instead of blending into it.
 */
export function citationMarkSx(theme: Theme, size: number, fontSize = Math.round(size * 0.5)) {
  const { tint, text } = citationMarkColors(theme)
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
    color: text,
    background: tint,
  } as const
}
