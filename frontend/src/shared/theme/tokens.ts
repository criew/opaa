/**
 * Design tokens of the OPAA interface - the single source of truth for every color, type,
 * spacing, radius, shadow and motion value. Values mirror docs/design/guidelines.md. Components
 * never reference scale values (blue, navy, gray) directly - they go through the semantic
 * roles below, so a branding override can replace the accent without touching
 * component code.
 */

/**
 * Accent and action color scale ("Blau"), derived from the brand concept's two blues: 500 is
 * Hellblau (`#009DDB`, accent lines and large type - white on it reaches only 3.07:1), 700 is
 * Mittelblau (`#0055A4`, accent text and filled action surface of the light scheme; the dark scheme
 * fills actions white, because Mittelblau reaches only 1.9:1 on Dunkelblau).
 */
export const blue = {
  50: '#EBF7FC',
  100: '#CCEBF8',
  200: '#99D8F1',
  300: '#66C4E9',
  400: '#33B1E2',
  500: '#009DDB',
  600: '#0079C0',
  700: '#0055A4',
  800: '#004686',
  900: '#00386C',
} as const

/**
 * Structure and text color scale ("Dunkelblau"). 800 is the brand's Dunkelblau `#242855`; the
 * darker steps carry the dark scheme, the lighter ones the hover and active tiles of the sidebar.
 */
export const navy = {
  950: '#151731',
  900: '#1B1E40',
  800: '#242855',
  700: '#2F335E',
  600: '#3E4269',
  500: '#666888',
} as const

/** Cool gray scale around the brand's gray `#555A78` (500), harmonised with the Dunkelblau. */
export const gray = {
  100: '#E7E8EC',
  200: '#CFD1D9',
  300: '#AAACBC',
  400: '#80839A',
  500: '#555A78',
  600: '#3F4468',
  700: '#30345E',
  800: '#20244C',
} as const

export const white = '#FFFFFF'
/** Light raised surface. */
export const offWhite = '#F6F8FB'
/** Light muted surface ("Hellblaugrau" of the brand concept). */
export const smoke = '#EEF3FB'

/**
 * Tones of a signal colour (guidelines 2.2), mapped onto MUI's main/dark/light. `main` is also a
 * text colour: >= 4.5:1 against bg-1..3 of the light, dark and rail schemes; bg-1 is the text on
 * every tone.
 */
export interface SignalTones {
  main: string
  /** Hover/pressed tone of filled surfaces: darker under light text, lighter under dark text. */
  hover: string
  /** Lighter variant (MUI `light`). */
  soft: string
}

/** Danger tones of the light schemes; the main tone is >= 4.5:1 against white, off-white, smoke. */
const dangerLight: SignalTones = { main: '#CE2C31', hover: '#901E22', soft: '#D23B40' }
/** Danger tones of the dark grounds; the main tone is >= 4.5:1 against the dark scheme's card. */
const dangerDark: SignalTones = { main: '#FF7A80', hover: '#FF8287', soft: '#FF9095' }
/** Light-scheme amber: the dark grounds' amber reaches only 1.78:1 as text on white. */
const warningLight: SignalTones = { main: '#8F5A00', hover: '#6B4300', soft: '#9A6100' }
const warningDark: SignalTones = { main: '#F5B83D', hover: '#F7C35D', soft: '#F8CC73' }
/** Light-scheme green: the dark grounds' green reaches only 2.59:1 as text on white. */
const successLight: SignalTones = { main: '#11774F', hover: '#0B5A3B', soft: '#0E7A52' }
const successDark: SignalTones = { main: '#38C18F', hover: '#5BCB9F', soft: '#7DD5B1' }

/**
 * Marker behind matched words (search hits), the same pale amber in both schemes; translucent, so
 * it takes on the surface below. fg-1 keeps >= 4.5:1 on it over bg-1..3 of either scheme.
 */
export const markHighlight = 'rgba(245, 184, 61, 0.25)'

/**
 * Roles of the document view behind „Dokument öffnen“ (guidelines 2.3). The paper stays light in
 * both schemes, so band, edge and label are the same in both: the band is an opaque marker that
 * is multiplied onto the page (dark text stays dark on it), the Mittelblau edge carries the 3:1 of
 * the mark against the paper, and white on it reaches 7.41:1 for the label „Fundstelle“.
 */
export interface DocumentViewRoles {
  /** The surface the pages lie on: light in the light scheme, navy in the dark one. */
  desk: string
  /** The paper of a Markdown, text, mail or spreadsheet original, light in both schemes. */
  paper: string
  /** Band behind the cited passage, on the paper only. */
  band: string
  /** Edge stroke beside the passage and surface of its label. */
  edge: string
  /** Text of the label on {@link edge}. */
  edgeFg: string
  /** The finding card and the panels beside the paper, apart from the desk in both schemes. */
  card: string
}

export const documentViewLight: DocumentViewRoles = {
  desk: gray[100],
  paper: white,
  band: blue[100],
  edge: blue[700],
  edgeFg: white,
  card: white,
}

export const documentViewDark: DocumentViewRoles = {
  ...documentViewLight,
  desk: navy[950],
  card: navy[900],
}

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
  /**
   * Action, reference, active state - as text and indicator colour. Replaceable via branding.
   * Proven >= 4.5:1 against bg-1..3 of its own scheme: blue-700 in the light scheme, blue-400
   * in the dark one, blue-300 on the lighter navy steps of sidebar and rail.
   */
  accent: string
  /** Text on accent surfaces: white on blue-700, Dunkelblau on the dark scheme's white surface. */
  accentFg: string
  /**
   * Filled action surfaces (contained buttons, filled primary chips), proven >= 4.5:1 under
   * accentFg: blue-700 in the light role sets, white in the dark scheme.
   */
  accentSurface: string
  /** Accent surface hover state (-8% lightness of the surface, sampled). */
  accentHover: string
  /** Accent surface active/pressed state (-16% lightness of the surface, sampled). */
  accentPress: string
  /** Standard border. */
  border: string
  /** Emphasised border (inputs, tables). */
  borderStrong: string
  /**
   * Danger - destructive actions and errors, as text, icon and filled surface. Proven >= 4.5:1
   * as text against bg-1..3 of the light and dark scheme; bg-1 is the text on its surface.
   */
  danger: string
  /**
   * Hover/pressed tone of filled danger surfaces (MUI `error.dark`). Darker than `danger` where
   * bg-1 is light text, lighter where bg-1 is dark text; bg-1 keeps >= 4.5:1 on it.
   */
  dangerHover: string
  /** Lighter danger variant (MUI `error.light`); bg-1 keeps >= 4.5:1 on it. */
  dangerSoft: string
  /** Warning - consequential but reversible states and actions, as text, icon and surface. */
  warning: SignalTones
  /** Success - completed and healthy states, as text, icon and surface. */
  success: SignalTones
}

export const lightRoles: SchemeRoles = {
  bg1: white,
  bg2: offWhite,
  bg3: smoke,
  fg1: navy[800],
  fg2: gray[600],
  // gray[500], not gray[400]: gray[400] only reaches 3.73:1 against white, below the 4.5:1
  // WCAG AA floor for the fg-3 role (tertiary text, table heads, metadata - see
  // frontend/src/shared/theme/theme.test.ts). gray[500] reaches 6.73:1.
  fg3: gray[500],
  // blue[700], not blue[500]: accent is also a text colour (links, footnote digits,
  // ghost buttons) and blue[500] reaches only 3.07:1 on white - blue[700] reaches 7.41:1.
  accent: blue[700],
  accentFg: white,
  accentSurface: blue[700],
  accentHover: blue[800],
  accentPress: blue[900],
  border: gray[100],
  borderStrong: gray[200],
  danger: dangerLight.main,
  dangerHover: dangerLight.hover,
  dangerSoft: dangerLight.soft,
  warning: warningLight,
  success: successLight,
}

export const darkRoles: SchemeRoles = {
  // Mirrors the logo on dark grounds: Dunkelblau is the page, cards (bg-2) are the style guide's
  // #2F3466, muted surfaces (bg-3) sit between the two.
  bg1: navy[800],
  bg2: '#2F3466',
  bg3: '#2A2E5E',
  fg1: white,
  fg2: '#C9DBF2',
  fg3: '#A9B6D6',
  // blue[400], not Hellblau: Hellblau reaches 4.53:1 on the page but only 3.79:1 on cards,
  // and accent is also a text colour. blue[400] keeps >= 4.73:1 on every dark surface.
  accent: blue[400],
  // Filled actions are white with Dunkelblau text: Mittelblau reaches only 1.9:1 on Dunkelblau.
  accentFg: navy[800],
  accentSurface: white,
  accentHover: '#E3EAF6',
  accentPress: '#C9DBF2',
  border: navy[600],
  borderStrong: navy[500],
  danger: dangerDark.main,
  dangerHover: dangerDark.hover,
  dangerSoft: dangerDark.soft,
  warning: warningDark,
  success: successDark,
}

/**
 * The Dunkelblau block of the sign-in page's brand panel. Its accent is blue-300: Hellblau falls
 * below 4.5:1 on the lighter navy steps.
 */
export const navyRoles: SchemeRoles = {
  bg1: navy[800],
  bg2: navy[700],
  bg3: navy[600],
  fg1: white,
  fg2: '#C8C9D4',
  fg3: '#A3A5B8',
  accent: blue[300],
  accentFg: white,
  accentSurface: blue[700],
  accentHover: blue[800],
  accentPress: blue[900],
  border: navy[700],
  borderStrong: navy[600],
  danger: dangerDark.main,
  dangerHover: dangerDark.hover,
  dangerSoft: dangerDark.soft,
  warning: warningDark,
  success: successDark,
}

/**
 * The global rail left of the space column: one shade darker than the navy
 * block so the two navigation levels read apart at a glance. bg2 is the hover fill, bg3 the
 * active tile (navy-800 hover, navy-700 active with a navy-600 border). In the dark
 * scheme the rail follows {@link darkRoles} like the sidebar, so the separation comes from the
 * border instead.
 */
export const railRoles: SchemeRoles = {
  bg1: navy[900],
  bg2: navy[800],
  bg3: navy[700],
  fg1: white,
  fg2: '#C8C9D4',
  fg3: '#A3A5B8',
  accent: blue[300],
  accentFg: white,
  accentSurface: blue[700],
  accentHover: blue[800],
  accentPress: blue[900],
  border: navy[800],
  borderStrong: navy[600],
  danger: dangerDark.main,
  dangerHover: dangerDark.hover,
  dangerSoft: dangerDark.soft,
  warning: warningDark,
  success: successDark,
}

/**
 * Font stacks (guidelines 3.1). Quicksand is the free relative of the Sklow brand face the
 * design uses (round-geometric, single-story g, SIL OFL); Inter stays in the stack as
 * fallback. Both and JetBrains Mono ship with the repo (SIL OFL); the
 * stacks deliberately end in system fonts so an operator-provided brand font can be loaded
 * in front of them later via the branding configuration.
 */
export const fontFamily = {
  sans: "'Quicksand', 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, system-ui, sans-serif",
  mono: "'JetBrains Mono', 'SF Mono', 'Menlo', 'Consolas', monospace",
} as const

/**
 * The six type steps of the app (guidelines 3.2). Components name a step, never a pixel value;
 * ESLint rejects a numeric `fontSize` under `features/` and `app/`.
 */
export const fontSize = {
  /** Uppercase section labels, table heads, overlines. */
  label: 11,
  /** Badges and chips, secondary facts next to a value (dates, counts), hints under a field. */
  meta: 12,
  /** Running text, field values, buttons, menu entries. */
  text: 14,
  /** Title of an area page, dialog or side panel; section heads. */
  title: 16,
  /** Title of a detail page. */
  detailTitle: 20,
  /** Step title in a wizard and other single-purpose pages. */
  wizardTitle: 24,
} as const

/**
 * Icon sizes in px (MUI sizes icons through `fontSize`). An icon next to text takes the size of
 * the text step it accompanies or one step up.
 */
export const iconSize = {
  xs: 14,
  sm: 16,
  md: 20,
  lg: 24,
  xl: 48,
} as const

/**
 * Heights of buttons and single-line fields (guidelines 5.1, 5.2): `regular` everywhere,
 * `compact` only in table rows, section heads, filter rows and toolbars.
 */
export const controlHeight = {
  regular: 36,
  compact: 32,
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

/**
 * The three content widths in px (guidelines 4.6). A page takes the width of its kind, so the
 * right edge stays put between two pages of the same kind: `form` for forms, wizards and pages
 * that carry only running text (a refusal, a hint), `detail` for detail pages, `list` for area
 * pages with a table. No theme writes a width as a number.
 */
export const contentWidth = {
  form: 720,
  detail: 880,
  list: 1180,
} as const

/** Longest line of an explaining text (guidelines 4.6), also above a wide table. */
export const readingWidth = '72ch'

/**
 * Widths in px of a single control or small block that must not stretch across its container: a
 * field with a short value, a search field, a narrow form group.
 */
export const fieldWidth = {
  short: 240,
  medium: 360,
  long: 480,
} as const

/**
 * Widths of forms (guidelines 5.2): a settings form is one column of `column` px, a field holding
 * only a number is `number` px wide.
 */
export const formWidth = {
  column: 640,
  number: 160,
} as const

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
 * Shape of every hand-built badge that is no MUI `Chip` (guidelines 5.5): pill radius, the meta step (12 px) and
 * weight 500, the same as the theme's `MuiChip`, so a badge never reads square, small or bold
 * beside a chip.
 */
export const badgeShape = {
  borderRadius: `${radius.pill}px`,
  fontSize: fontSize.meta,
  fontWeight: fontWeight.medium,
} as const

/**
 * Shadows are reserved for floating layers (guidelines 4.3) - resting cards separate through
 * borders and surface steps, never through shadows.
 */
export const shadow = {
  /** Hairline lift for resting rows that need a whisper of depth (input row). */
  hairline: '0 1px 2px rgba(36, 40, 85, 0.06)',
  /** Level 2: menus, popovers, suggestion lists. */
  floating: '0 2px 6px rgba(36, 40, 85, 0.08)',
  /** Level 3: dialogs, side panels. */
  overlay: '0 8px 24px rgba(36, 40, 85, 0.10)',
} as const

/**
 * Focus ring (guidelines 4.4): an opaque accent line, so it keeps 3:1 against every surface the
 * accent is proven on. The offset leaves a gap of the ground between ring and element, which
 * keeps the ring visible on filled accent surfaces.
 */
export const focusRingWidthPx = 2
export const focusRingOffsetPx = 2

/** Motion (guidelines 4.5): only transform/opacity, reduced motion collapses to state swaps. */
export const motion = {
  durationFastMs: 120,
  durationBaseMs: 200,
  durationSlowMs: 360,
  easeOut: 'cubic-bezier(0.22, 1, 0.36, 1)',
  easeInOut: 'cubic-bezier(0.65, 0, 0.35, 1)',
} as const

/**
 * Operator branding hooks (guidelines 7): the theme takes the backend's branding settings, so no
 * component ever hardwires the accent.
 */
export interface BrandingOverrides {
  /** Replaces the accent role; hover/press/focus states are derived from it. */
  primaryColor?: string
}
