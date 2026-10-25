import type { ReactNode } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import useMediaQuery from '@mui/material/useMediaQuery'
import { alpha, keyframes, useTheme } from '@mui/material/styles'
import BrandMark from '@/shared/ui/BrandMark'
import NotificationHost from '@/shared/ui/feedback/NotificationHost'
import { useBrandingStore } from '@/shared/branding/brandingStore'
import { navyRoles } from '@/shared/theme/tokens'
import bundledBackdropUrl from '../assets/login-backdrop.webp'

// One staged entrance for the form column (guidelines 4.5); the theme collapses it under
// prefers-reduced-motion.
const formReveal = keyframes`
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: none; }
`

/**
 * Opacity of the scrim over an uploaded background image.
 *
 * An installation brings the image itself; this application does not know how bright it is. The
 * scrim is therefore fixed and not derived from the image: at this opacity the text of the
 * brand area still reaches the 4.5:1 from docs/design/accessibility.md even over a pure white
 * image — recalculated in AuthLayout.contrast.test.ts, the only place
 * where this value can be checked at all.
 */
export const LOGIN_BACKDROP_SCRIM_OPACITY = 0.82

/**
 * The backdrop OPAA ships for the brand area; an installation's uploaded image replaces it.
 * Origin, prompt and processing: docs/design/anmeldeseite-hintergrundbild.md.
 */
const DEFAULT_LOGIN_BACKDROP_URL = bundledBackdropUrl

/**
 * Opacity of the scrim over the bundled backdrop. Unlike an uploaded image this one is known:
 * AuthLayout.contrast.test.ts proves the 4.5:1 of brand name and claim against the brightest
 * pixel the shipped file actually contains (measured by scripts/measure-login-backdrop.mjs and
 * pinned to the file's checksum) — which is what allows a lighter scrim than over uploads.
 */
export const LOGIN_BACKDROP_DEFAULT_SCRIM_OPACITY = 0.56

/**
 * Opacity of the claim on the brand area — the weaker of the two texts there and thus the
 * decisive value for the contrast proof over a background image.
 */
export const LOGIN_CLAIM_OPACITY = 0.78

/** The installation's brand, large — the content of the area that used to be empty. */
function BrandPanel() {
  const claim = useBrandingStore((s) => s.branding.claim)
  return (
    <Box sx={{ maxWidth: 460 }}>
      {/* The login page's logo carries this area, so it is shown clearly
         larger here than the brand in the sidebar. */}
      <BrandMark logoHeight={96} variant="h4" preferLoginLogo />
      {claim && (
        <Typography
          sx={{
            mt: 2,
            fontSize: { md: 17, lg: 19 },
            lineHeight: 1.5,
            color: alpha(navyRoles.fg1, LOGIN_CLAIM_OPACITY),
            maxWidth: '28ch',
          }}
        >
          {claim}
        </Typography>
      )}
    </Box>
  )
}

/**
 * The frame of every screen before the session: from `md` on, the installation's brand on the
 * left on navy, the input on the right; below that a column with a small brand above the input.
 *
 * The input column keeps a 440 px reading width even where there would be room: beyond roughly
 * 500 px, label and field stand so far apart that the association suffers. The width of a
 * screen therefore goes into the area next to it, never into the form.
 *
 * The brand belongs at every width, not only as decoration: setting a password and
 * confirming an email are reached cold from an email, and there it must be recognizable whose
 * installation is asking.
 */
export default function AuthLayout({ children }: { children: ReactNode }) {
  const theme = useTheme()
  // Only one of the two brands is built, not one of them hidden: the same name twice in the
  // tree is a duplicate even when one half is invisible.
  const wide = useMediaQuery(theme.breakpoints.up('md'))
  const uploadedBackgroundUrl = useBrandingStore((s) => s.branding.loginBackgroundUrl)

  // The scrim lies above the image as its own layer, in the same background-image property — a
  // gradient of two equal stops is the only form in which CSS lays a translucent colour over an
  // image without needing a second element for it. An uploaded image gets the worst-case scrim;
  // the bundled backdrop the lighter one its own contrast proof allows.
  const scrim = alpha(
    navyRoles.bg1,
    uploadedBackgroundUrl ? LOGIN_BACKDROP_SCRIM_OPACITY : LOGIN_BACKDROP_DEFAULT_SCRIM_OPACITY,
  )
  const panelBackgroundImage = `linear-gradient(${scrim}, ${scrim}), url("${uploadedBackgroundUrl ?? DEFAULT_LOGIN_BACKDROP_URL}")`

  return (
    <Box
      sx={{
        display: 'grid',
        gridTemplateColumns: wide ? 'minmax(0, 1fr) minmax(0, 1fr)' : 'minmax(0, 1fr)',
        minHeight: '100vh',
      }}
    >
      {wide && (
        <Box
          data-testid="auth-brand-panel"
          sx={{
            display: 'flex',
            flexDirection: 'column',
            justifyContent: 'center',
            px: { md: 6, lg: 10 },
            py: 6,
            // The navy stays underneath: it is what shows until the image has loaded and what
            // the scrim is composited onto in the contrast proof.
            bgcolor: navyRoles.bg1,
            color: navyRoles.fg1,
            backgroundImage: panelBackgroundImage,
            backgroundSize: 'cover',
            backgroundPosition: 'center',
          }}
        >
          <BrandPanel />
        </Box>
      )}

      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          px: { xs: 2.5, sm: 4 },
          py: { xs: 4, md: 6 },
          bgcolor: 'background.default',
        }}
      >
        <Box
          sx={{
            width: '100%',
            maxWidth: 440,
            animation: `${formReveal} 240ms ease-out both`,
          }}
        >
          {/* Without the brand area next to it, the input column carries the brand itself. */}
          {!wide && (
            <Box sx={{ mb: 3.5 }}>
              <BrandMark
                orientation="vertical"
                variant="h5"
                logoHeight={56}
                showClaim
                preferLoginLogo
              />
            </Box>
          )}
          {children}
        </Box>
      </Box>

      {/* The popup notifications of these screens would otherwise go nowhere: the app-wide host
          hangs in AppShell, and nothing before a session renders inside it (guidelines 5.9). */}
      <NotificationHost />
    </Box>
  )
}
