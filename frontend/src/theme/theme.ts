import { alpha, createTheme, darken } from '@mui/material/styles'
import type { Theme } from '@mui/material/styles'
import type { PaletteMode } from '@mui/material'
import type { BrandingOverrides, SchemeRoles } from './tokens'
import {
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
  radius,
  semanticColors,
  shadow,
  white,
} from './tokens'

export const CHAT_MAX_WIDTH = '896px'

/** Guidelines 5.1: comfortable and compact control heights. */
const CONTROL_HEIGHT = 40
const CONTROL_HEIGHT_COMPACT = 32

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
  const isDark = mode === 'dark'
  const roles = isDark ? darkRoles : lightRoles
  const { accent, accentHover, accentPress } = resolveAccent(roles, branding)
  const focusRing = alpha(accent, focusRingAlpha)

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
    typography: {
      fontFamily: fontFamily.sans,
      h1: {
        fontSize: fontSize.xl3,
        fontWeight: fontWeight.bold,
        lineHeight: lineHeight.tight,
        letterSpacing: letterSpacing.tight,
      },
      h2: {
        fontSize: fontSize.xl2,
        fontWeight: fontWeight.bold,
        lineHeight: lineHeight.tight,
        letterSpacing: letterSpacing.tight,
      },
      h3: {
        fontSize: fontSize.xl,
        fontWeight: fontWeight.semibold,
        lineHeight: lineHeight.snug,
        letterSpacing: letterSpacing.tight,
      },
      h4: {
        fontSize: fontSize.lg,
        fontWeight: fontWeight.semibold,
        lineHeight: lineHeight.snug,
      },
      h5: {
        fontSize: fontSize.md,
        fontWeight: fontWeight.semibold,
        lineHeight: lineHeight.snug,
      },
      h6: {
        fontSize: fontSize.base,
        fontWeight: fontWeight.semibold,
        lineHeight: lineHeight.snug,
      },
      body1: {
        fontSize: fontSize.base,
        lineHeight: lineHeight.normal,
        letterSpacing: letterSpacing.normal,
      },
      body2: {
        fontSize: fontSize.sm,
        lineHeight: lineHeight.normal,
        letterSpacing: letterSpacing.normal,
      },
      caption: {
        fontSize: fontSize.xs,
        lineHeight: lineHeight.snug,
      },
      overline: {
        fontSize: fontSize.xs2,
        fontWeight: fontWeight.medium,
        letterSpacing: letterSpacing.caps,
        textTransform: 'uppercase',
        lineHeight: lineHeight.snug,
      },
      button: {
        fontSize: fontSize.sm,
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
          // Visible focus for every interactive element, in both schemes (guidelines 4.4).
          '*:focus-visible': {
            outline: `${focusRingWidthPx}px solid ${focusRing}`,
            outlineOffset: '2px',
          },
          '@media (prefers-reduced-motion: reduce)': {
            '*, *::before, *::after': {
              animationDuration: '0.01ms !important',
              animationIterationCount: '1 !important',
              transitionDuration: '0.01ms !important',
            },
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
            borderRadius: radius.md,
            minHeight: CONTROL_HEIGHT,
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
              borderColor: roles.borderStrong,
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
            fontSize: fontSize.sm,
            '&:hover': {
              backgroundColor: roles.bg2,
            },
          },
        },
      },
      MuiChip: {
        styleOverrides: {
          root: {
            borderRadius: radius.pill,
            fontSize: fontSize.xs,
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
            backgroundColor: isDark ? gray[100] : navy[700],
            color: isDark ? navy[800] : white,
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
