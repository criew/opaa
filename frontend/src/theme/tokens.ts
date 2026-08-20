/**
 * Design tokens of the OPAA interface - the single source of truth for every color, type,
 * spacing, radius, shadow and motion value. Values mirror docs/design/guidelines.md, which in
 * turn is derived from the target-state mockups (docs/design/OPAA Mockups.html). Components
 * never reference scale values (blue, navy, gray) directly - they go through the semantic
 * roles below, so a branding override (#582/#583) can replace the accent without touching
 * component code.
 */

/** Accent and action color scale ("Blau"), sampled from the corporate master assets. */
export const blue = {
  50: '#E7F4FE',
  100: '#C6E3FC',
  200: '#9BCEFA',
  300: '#61B5F6',
  400: '#349EF2',
  500: '#1292EE',
  600: '#0F80D6',
  700: '#0B6FBC',
  800: '#085B9C',
  900: '#05447A',
} as const

/** Structure and text color scale ("Navy"). 800 is the base navy of the brand. */
export const navy = {
  900: '#00152D',
  800: '#012142',
  700: '#02305E',
  600: '#034079',
  500: '#055396',
} as const

/** Cool gray scale, harmonised with the navy. */
export const gray = {
  100: '#E6EBF1',
  200: '#CBD4DF',
  300: '#A4B1C1',
  400: '#778797',
  500: '#556473',
  600: '#3B4958',
  700: '#26323F',
  800: '#162231',
} as const

/**
 * Neutral dark scale of the dark scheme (#654) - aligned with the dark scheme of the Claude
 * docs site (code.claude.com, sampled 2026-08-20): a near-black, neutral gray with clear
 * surface steps instead of navy-on-navy. Navy remains the sidebar's block in the light scheme.
 */
export const carbon = {
  950: '#09090B',
  900: '#171717',
  850: '#1F1F1F',
  800: '#252525',
  700: '#333333',
} as const

export const white = '#FFFFFF'
/** Light raised surface. */
export const offWhite = '#F6F8FB'
/** Light muted surface and rules. */
export const smoke = '#EEF2F7'

/** Meaning-only colors (guidelines 2.1): success, warning, danger - never decorative. */
export const semanticColors = {
  success: '#16B77B',
  warning: '#F5B83D',
  danger: '#E5484D',
} as const

/**
 * Semantic roles - the vocabulary every component uses (guidelines 2.2). One value set per
 * color scheme; both schemes are equally binding.
 */
export interface SchemeRoles {
  /** Page ground. */
  bg1: string
  /** Raised surface (cards, headers). */
  bg2: string
  /** Muted surface (inputs, table heads). */
  bg3: string
  /** Primary text. */
  fg1: string
  /** Secondary text. */
  fg2: string
  /** Tertiary text and metadata. */
  fg3: string
  /** Action, reference, active state. Replaceable via branding. */
  accent: string
  /** Text on accent surfaces. */
  accentFg: string
  /** Accent hover state (-8% lightness, sampled). */
  accentHover: string
  /** Accent active/pressed state (-16% lightness, sampled). */
  accentPress: string
  /** Standard border. */
  border: string
  /** Emphasised border (inputs, tables). */
  borderStrong: string
}

export const lightRoles: SchemeRoles = {
  bg1: white,
  bg2: offWhite,
  bg3: smoke,
  fg1: navy[800],
  fg2: gray[600],
  fg3: gray[400],
  accent: blue[500],
  accentFg: white,
  accentHover: blue[600],
  accentPress: blue[700],
  border: gray[100],
  borderStrong: gray[200],
}

export const darkRoles: SchemeRoles = {
  bg1: carbon[950],
  bg2: carbon[900],
  bg3: carbon[850],
  fg1: '#DEDEDE',
  fg2: '#9E9E9E',
  fg3: '#8A8A8A',
  accent: blue[500],
  accentFg: white,
  accentHover: blue[600],
  accentPress: blue[700],
  border: carbon[800],
  borderStrong: carbon[700],
}

/**
 * The navy block the sidebar keeps in the light scheme (mockup 1a) - the former dark-scheme
 * values, now scoped to that one surface. In the dark scheme the sidebar follows
 * {@link darkRoles} instead (#654).
 */
export const navyRoles: SchemeRoles = {
  bg1: navy[800],
  bg2: navy[700],
  bg3: navy[600],
  fg1: white,
  fg2: '#B9C6D4',
  fg3: '#7A8BA0',
  accent: blue[500],
  accentFg: white,
  accentHover: blue[600],
  accentPress: blue[700],
  border: 'rgba(255, 255, 255, 0.08)',
  borderStrong: 'rgba(255, 255, 255, 0.14)',
}

/**
 * Font stacks (guidelines 3.1). Inter and JetBrains Mono ship with the repo (SIL OFL); the
 * stacks deliberately end in system fonts so an operator-provided brand font can be loaded
 * in front of them later via the branding configuration.
 */
export const fontFamily = {
  sans: "'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, system-ui, sans-serif",
  mono: "'JetBrains Mono', 'SF Mono', 'Menlo', 'Consolas', monospace",
} as const

/** Fixed pixel steps (guidelines 3.2). The app uses 11-30px; larger steps are brand moments. */
export const fontSize = {
  xs2: 11,
  xs: 12,
  sm: 14,
  base: 16,
  md: 18,
  lg: 20,
  xl: 24,
  xl2: 30,
  xl3: 36,
  xl4: 48,
  xl5: 64,
  xl6: 80,
  xl7: 104,
} as const

/** Inter weights (guidelines 3.3). Nothing above 700. */
export const fontWeight = {
  regular: 400,
  medium: 500,
  semibold: 600,
  bold: 700,
} as const

export const lineHeight = {
  tight: 1.1,
  snug: 1.25,
  normal: 1.5,
  loose: 1.7,
} as const

export const letterSpacing = {
  tight: '-0.02em',
  normal: '-0.005em',
  caps: '0.08em',
} as const

/**
 * Spacing follows the 4px grid (guidelines 4.1). MUI's spacing factor stays at its default
 * of 8px - every existing `sx` step keeps its size, and half steps (`theme.spacing(0.5)`)
 * produce the 4px base unit, so the grid holds without a breaking rescale.
 */
export const spacingGridPx = 4

/** Radii (guidelines 4.2). `md` is the 10px default for cards, inputs, buttons, dialogs. */
export const radius = {
  xs: 4,
  sm: 6,
  md: 10,
  lg: 16,
  xl: 24,
  pill: 999,
} as const

/**
 * Shadows are reserved for floating layers (guidelines 4.3) - resting cards separate through
 * borders and surface steps, never through shadows.
 */
export const shadow = {
  /** Level 2: menus, popovers, suggestion lists. */
  floating: '0 2px 6px rgba(1, 32, 66, 0.08)',
  /** Level 3: dialogs, side panels. */
  overlay: '0 8px 24px rgba(1, 32, 66, 0.10)',
} as const

/** Focus ring opacity over the accent color (guidelines 4.4). */
export const focusRingAlpha = 0.32
export const focusRingWidthPx = 3

/** Motion (guidelines 4.5): only transform/opacity, reduced motion collapses to state swaps. */
export const motion = {
  durationFastMs: 120,
  durationBaseMs: 200,
  durationSlowMs: 360,
  easeOut: 'cubic-bezier(0.22, 1, 0.36, 1)',
  easeInOut: 'cubic-bezier(0.65, 0, 0.35, 1)',
} as const

/**
 * Operator branding hooks (guidelines 7). The backend settings arrive with #582/#583; the
 * theme already accepts them so no component ever hardwires the accent.
 */
export interface BrandingOverrides {
  /** Replaces the accent role; hover/press/focus states are derived from it. */
  primaryColor?: string
}
