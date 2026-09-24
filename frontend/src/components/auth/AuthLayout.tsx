import type { ReactNode } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import useMediaQuery from '@mui/material/useMediaQuery'
import { alpha, keyframes, useTheme } from '@mui/material/styles'
import BrandMark from '../BrandMark'
import NotificationHost from '../NotificationHost'
import { useBrandingStore } from '../../stores/brandingStore'
import { navyRoles } from '../../theme/tokens'

// One staged entrance for the form column (guidelines 4.5); the theme collapses it under
// prefers-reduced-motion.
const formReveal = keyframes`
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: none; }
`

/**
 * Deckkraft des Schleiers über einem hinterlegten Hintergrundbild (#1910).
 *
 * Das Bild bringt eine Installation selbst mit; wie hell es ist, weiß diese Anwendung nicht. Der
 * Schleier ist deshalb fest und nicht aus dem Bild abgeleitet: Bei dieser Deckkraft erreicht die
 * Schrift der Markenfläche auch über einem rein weißen Bild noch die 4,5:1 aus
 * docs/design/accessibility.md — nachgerechnet in AuthLayout.contrast.test.ts, dem einzigen Ort,
 * an dem dieser Wert überhaupt prüfbar ist.
 */
export const LOGIN_BACKDROP_SCRIM_OPACITY = 0.82

/**
 * Deckkraft des Claims auf der Markenfläche — der schwächere der beiden Texte dort und damit der
 * maßgebliche Wert für den Kontrastnachweis über einem Hintergrundbild.
 */
export const LOGIN_CLAIM_OPACITY = 0.78

/** Die Marke der Installation, groß — der Inhalt der Fläche, die vorher leer war. */
function BrandPanel() {
  const claim = useBrandingStore((s) => s.branding.claim)
  return (
    <Box sx={{ maxWidth: 460 }}>
      {/* #1910: Das Logo der Anmeldeseite trägt diese Fläche, also steht es hier deutlich
          größer als die Marke in der Seitenleiste. */}
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
 * Der Rahmen jedes Bildschirms vor der Sitzung (#1627): ab `md` links die Marke der Installation
 * auf Navy, rechts die Eingabe; darunter eine Spalte mit kleiner Marke über der Eingabe.
 *
 * Die Eingabespalte behält 440 px Lesebreite, auch wo Platz wäre: Jenseits von etwa 500 px stehen
 * Beschriftung und Feld so weit auseinander, dass die Zuordnung leidet. Die Breite eines
 * Bildschirms geht deshalb in die Fläche daneben, nie in das Formular.
 *
 * Die Marke gehört in jeder Breite dazu, nicht nur als Schmuck: Passwort-Festlegen und
 * E-Mail-Bestätigung werden kalt aus einer E-Mail erreicht, und dort muss erkennbar sein, wessen
 * Installation fragt (#583).
 */
export default function AuthLayout({ children }: { children: ReactNode }) {
  const theme = useTheme()
  // Nur eine der beiden Marken wird gebaut, nicht eine davon versteckt: Zweimal derselbe Name im
  // Baum ist auch dann eine Dopplung, wenn eine Hälfte unsichtbar ist.
  const wide = useMediaQuery(theme.breakpoints.up('md'))
  const backgroundUrl = useBrandingStore((s) => s.branding.loginBackgroundUrl)

  // Ohne Bild bleibt die heutige Fläche (#1910): derselbe weiche Kern auf Navy. Mit Bild liegt
  // der Schleier als eigene Ebene darüber, in derselben background-image-Eigenschaft — ein
  // Farbverlauf aus zwei gleichen Stopps ist die einzige Form, in der CSS eine Deckfarbe über
  // ein Bild legt, ohne dafür ein zweites Element zu brauchen.
  const scrim = alpha(navyRoles.bg1, LOGIN_BACKDROP_SCRIM_OPACITY)
  const panelBackgroundImage = backgroundUrl
    ? `linear-gradient(${scrim}, ${scrim}), url("${backgroundUrl}")`
    : `radial-gradient(70% 55% at 20% 22%, ${alpha(navyRoles.accent, 0.28)} 0%, transparent 72%)`

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
            bgcolor: navyRoles.bg1,
            color: navyRoles.fg1,
            // Tiefe statt einer flachen Fläche: ein weicher Kern oben links, gerade so viel, dass
            // die Fläche nicht als Block liegt (guidelines 4.3 - Atmosphäre, keine Effekte).
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
          {/* Ohne die Markenfläche daneben trägt die Eingabespalte die Marke selbst. */}
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
