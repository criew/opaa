import { alpha, createTheme, darken } from '@mui/material/styles'
import type { Theme } from '@mui/material/styles'
import type { PaletteMode } from '@mui/material'
import type { BrandingOverrides, SchemeRoles } from './tokens'
import {
  blue,
  carbon,
  darkRoles,
  focusRingAlpha,
  focusRingWidthPx,
  fontFamily,
  fontSize,
  fontWeight,
  gray,
  letterSpacing,
  lightRoles,
  lineHeight,
  motion,
  navy,
  navyRoles,
  radius,
  semanticColors,
  shadow,
  white,
} from './tokens'

export const CHAT_MAX_WIDTH = '896px'

/** Guidelines 5.1: comfortable and compact control heights. */
const CONTROL_HEIGHT = 34
const CONTROL_HEIGHT_COMPACT = 28

/**
 * Resolves the effective accent states. Without branding, the exact sampled mockup values
 * apply; a configured brand color derives hover (-8%), press (-16%) and the focus ring from
 * itself instead of falling back to the blue scale (guidelines 7).
 */
function resolveAccent(roles: SchemeRoles, branding?: BrandingOverrides) {
  const brandColor = branding?.primaryColor
  if (!brandColor) {
    return {
      accent: roles.accent,
      accentHover: roles.accentHover,
      accentPress: roles.accentPress,
    }
  }
  return {
    accent: brandColor,
    accentHover: darken(brandColor, 0.08),
    accentPress: darken(brandColor, 0.16),
  }
}

/**
 * Builds the app theme from the token layer - the only place where tokens are mapped onto
 * MUI. Both schemes are equally binding (guidelines, preamble); surfaces separate through
 * borders and surface steps, shadows are reserved for floating layers.
 */
export function createAppTheme(mode: PaletteMode, branding?: BrandingOverrides): Theme {
  return buildTheme(mode, mode === 'dark' ? darkRoles : lightRoles, branding)
}

/**
 * The sidebar's own theme (#587/#654): while the app is light it keeps the mockup's navy block
 * ({@link navyRoles}, guidelines 2.3); while the app is dark it follows the carbon dark scheme
 * like the rest of the interface. Always dark-mode MUI semantics, since both surfaces are dark.
 */
export function createSidebarTheme(appMode: PaletteMode, branding?: BrandingOverrides): Theme {
  return buildTheme('dark', appMode === 'light' ? navyRoles : darkRoles, branding)
}

function buildTheme(mode: PaletteMode, roles: SchemeRoles, branding?: BrandingOverrides): Theme {
  const isDark = mode === 'dark'
  const { accent, accentHover, accentPress } = resolveAccent(roles, branding)
  const focusRing = alpha(accent, focusRingAlpha)
  // One focus ring for everything interactive, in both schemes (guidelines 4.4). Shared between
  // the global rule and MuiButtonBase, which resets `outline` itself and would otherwise win.
  const focusRingStyle = {
    outline: `${focusRingWidthPx}px solid ${focusRing}`,
    outlineOffset: '2px',
  }

  return createTheme({
    palette: {
      mode,
      primary: {
        main: accent,
        dark: accentPress,
        contrastText: roles.accentFg,
      },
      secondary: {
        main: roles.fg2,
        contrastText: roles.bg1,
      },
      error: { main: semanticColors.danger },
      warning: { main: semanticColors.warning },
      success: { main: semanticColors.success },
      background: {
        default: roles.bg1,
        paper: roles.bg1,
      },
      divider: roles.border,
      text: {
        primary: roles.fg1,
        secondary: roles.fg2,
        disabled: roles.fg3,
      },
    },
    // The fine grid below is sampled from mockup 1a's inline styles (#658) - the app surface
    // runs deliberately smaller and denser than the token scale's headline steps.
    typography: {
      fontFamily: fontFamily.sans,
      h1: {
        fontSize: 27,
        fontWeight: fontWeight.semibold,
        lineHeight: lineHeight.tight,
        letterSpacing: letterSpacing.tight,
      },
      h2: {
        fontSize: 26,
        fontWeight: fontWeight.semibold,
        lineHeight: lineHeight.tight,
        letterSpacing: letterSpacing.tight,
      },
      h3: {
        fontSize: 20,
        fontWeight: fontWeight.semibold,
        lineHeight: lineHeight.snug,
        letterSpacing: letterSpacing.tight,
      },
      h4: {
        fontSize: 18,
        fontWeight: fontWeight.semibold,
        lineHeight: lineHeight.snug,
      },
      h5: {
        fontSize: 16,
        fontWeight: fontWeight.semibold,
        lineHeight: lineHeight.snug,
      },
      h6: {
        fontSize: 14.5,
        fontWeight: fontWeight.semibold,
        lineHeight: lineHeight.snug,
      },
      body1: {
        fontSize: 14.5,
        lineHeight: 1.65,
        letterSpacing: letterSpacing.normal,
      },
      body2: {
        fontSize: 13,
        lineHeight: lineHeight.normal,
        letterSpacing: letterSpacing.normal,
      },
      subtitle1: {
        fontSize: 14.5,
        fontWeight: fontWeight.medium,
        lineHeight: lineHeight.snug,
      },
      subtitle2: {
        fontSize: 13,
        fontWeight: fontWeight.medium,
        lineHeight: lineHeight.snug,
      },
      caption: {
        fontSize: 11,
        lineHeight: lineHeight.snug,
      },
      overline: {
        fontSize: 9.5,
        fontWeight: fontWeight.medium,
        letterSpacing: '0.12em',
        textTransform: 'uppercase',
        lineHeight: lineHeight.snug,
      },
      button: {
        fontSize: 13.5,
        fontWeight: fontWeight.medium,
        textTransform: 'none',
        letterSpacing: letterSpacing.normal,
      },
    },
    shape: {
      borderRadius: radius.md,
    },
    transitions: {
      duration: {
        shortest: motion.durationFastMs,
        shorter: motion.durationFastMs,
        short: motion.durationBaseMs,
        standard: motion.durationBaseMs,
        complex: motion.durationSlowMs,
        enteringScreen: motion.durationBaseMs,
        leavingScreen: motion.durationFastMs,
      },
      easing: {
        easeOut: motion.easeOut,
        easeInOut: motion.easeInOut,
      },
    },
    components: {
      MuiCssBaseline: {
        styleOverrides: {
          body: {
            backgroundColor: roles.bg1,
          },
          '*:focus-visible': focusRingStyle,
          // Motion collapses to state changes; smooth scrolling becomes instant (guidelines 4.5).
          '@media (prefers-reduced-motion: reduce)': {
            '*, *::before, *::after': {
              animationDuration: '0.01ms !important',
              animationIterationCount: '1 !important',
              transitionDuration: '0.01ms !important',
              scrollBehavior: 'auto !important',
            },
          },
        },
      },
      MuiButtonBase: {
        styleOverrides: {
          root: {
            // ButtonBase sets `outline: 0` at the same specificity as the global rule above and
            // later in the sheet, so every button would lose its ring without this override.
            '&:focus-visible, &.Mui-focusVisible': focusRingStyle,
          },
        },
      },
      MuiPaper: {
        styleOverrides: {
          root: {
            backgroundImage: 'none',
          },
          // Resting surfaces separate through borders, not shadows (guidelines 4.3).
          elevation1: {
            boxShadow: 'none',
            border: `1px solid ${roles.border}`,
          },
          elevation2: {
            boxShadow: shadow.floating,
            border: `1px solid ${roles.border}`,
          },
          elevation3: {
            boxShadow: shadow.floating,
            border: `1px solid ${roles.border}`,
          },
          elevation4: {
            boxShadow: shadow.floating,
            border: `1px solid ${roles.border}`,
          },
        },
      },
      MuiButton: {
        defaultProps: {
          disableElevation: true,
        },
        styleOverrides: {
          root: {
            borderRadius: radius.sm,
            minHeight: CONTROL_HEIGHT,
            paddingLeft: 18,
            paddingRight: 18,
            variants: [
              {
                props: { variant: 'contained', color: 'primary' },
                style: {
                  backgroundColor: accent,
                  color: roles.accentFg,
                  '&:hover': {
                    backgroundColor: accentHover,
                  },
                  '&:active': {
                    backgroundColor: accentPress,
                  },
                },
              },
              {
                props: { variant: 'outlined' },
                style: {
                  borderColor: roles.borderStrong,
                  color: roles.fg1,
                  '&:hover': {
                    backgroundColor: roles.bg2,
                    borderColor: roles.borderStrong,
                  },
                  '&:active': {
                    backgroundColor: roles.bg3,
                  },
                },
              },
              {
                props: { variant: 'text' },
                style: {
                  '&:hover': {
                    backgroundColor: roles.bg2,
                  },
                  '&:active': {
                    backgroundColor: roles.bg3,
                  },
                },
              },
            ],
          },
          sizeSmall: {
            minHeight: CONTROL_HEIGHT_COMPACT,
          },
        },
      },
      MuiOutlinedInput: {
        styleOverrides: {
          root: {
            backgroundColor: roles.bg3,
            borderRadius: radius.md,
            '& .MuiOutlinedInput-notchedOutline': {
              // Mockup 1a draws inputs with the crisper gray-300 line (#658).
              borderColor: isDark ? roles.borderStrong : gray[300],
            },
            '&:hover .MuiOutlinedInput-notchedOutline': {
              borderColor: roles.fg3,
            },
            '&.Mui-focused .MuiOutlinedInput-notchedOutline': {
              borderColor: accent,
              borderWidth: 1,
            },
            '&.Mui-focused': {
              boxShadow: `0 0 0 ${focusRingWidthPx}px ${focusRing}`,
            },
          },
        },
      },
      MuiTableCell: {
        styleOverrides: {
          root: {
            borderBottomColor: roles.border,
          },
          // Eyebrow-style column heads (guidelines 5.3).
          head: {
            fontSize: fontSize.xs2,
            fontWeight: fontWeight.medium,
            letterSpacing: letterSpacing.caps,
            textTransform: 'uppercase',
            color: roles.fg3,
          },
        },
      },
      MuiTableRow: {
        styleOverrides: {
          root: {
            '&:hover': {
              backgroundColor: roles.bg2,
            },
          },
        },
      },
      MuiDialog: {
        styleOverrides: {
          paper: {
            borderRadius: radius.md,
            border: `1px solid ${roles.border}`,
            boxShadow: shadow.overlay,
          },
        },
      },
      MuiMenu: {
        styleOverrides: {
          paper: {
            borderRadius: radius.md,
            border: `1px solid ${roles.border}`,
            boxShadow: shadow.floating,
          },
        },
      },
      MuiPopover: {
        styleOverrides: {
          paper: {
            borderRadius: radius.md,
            border: `1px solid ${roles.border}`,
            boxShadow: shadow.floating,
          },
        },
      },
      MuiMenuItem: {
        styleOverrides: {
          root: {
            fontSize: 13,
            '&:hover': {
              backgroundColor: roles.bg2,
            },
            // Mockup 1a: the selected space sits on the blue-50 tint (#658).
            '&.Mui-selected': {
              backgroundColor: isDark ? alpha(accent, 0.16) : blue[50],
            },
          },
        },
      },
      MuiChip: {
        styleOverrides: {
          root: {
            borderRadius: radius.pill,
            fontSize: 11,
            fontWeight: fontWeight.medium,
          },
          outlined: {
            borderColor: roles.borderStrong,
            color: roles.fg2,
          },
        },
      },
      MuiTooltip: {
        styleOverrides: {
          tooltip: {
            backgroundColor: isDark ? carbon[700] : navy[700],
            color: isDark ? roles.fg1 : white,
            fontSize: fontSize.xs,
            borderRadius: radius.sm,
          },
        },
      },
      MuiLink: {
        styleOverrides: {
          root: {
            color: accent,
          },
        },
      },
    },
  })
}
