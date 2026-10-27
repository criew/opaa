import { fontSize, fontWeight, letterSpacing, lineHeight } from '@/shared/theme/tokens'

/**
 * The vertical rhythm of the assistant in MUI spacing steps (8px each, so every value lies on the
 * 4px grid of guidelines 4.1): lines that belong together sit close, groups stand apart.
 */
export const STEP_RHYTHM = {
  /** Page heading above the step's block. */
  pageToStep: 3,
  /** Eyebrow to title and title to description. */
  withinHeading: 1,
  /** Description, failure notice and the fields as separate groups. */
  betweenGroups: 3,
  /** A field and the quiet action that belongs to it, e.g. removing a stored key. */
  fieldToOwnAction: 1,
  /** Last field to the action bar. */
  toActions: 4,
  /** The action bar's divider to its buttons. */
  actionsDivider: 2,
} as const

/** The step's title (h2), scale step xl with the heading line height and tracking (guidelines 3). */
export const STEP_TITLE_SX = {
  fontSize: fontSize.xl,
  fontWeight: fontWeight.semibold,
  lineHeight: lineHeight.snug,
  letterSpacing: letterSpacing.tight,
} as const

/**
 * Quiet text buttons below a field start with their label on the field's left edge: a narrow
 * inner padding, taken back by the same negative margin, keeps a hover surface around the label.
 */
export const EDGE_ALIGNED_TEXT_BUTTON_SX = { px: 1, ml: -1 } as const
