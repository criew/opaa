import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import Paper from '@mui/material/Paper'
import Typography from '@mui/material/Typography'
import { ThemeProvider } from '@mui/material/styles'
import { useMemo } from 'react'
import type { PaletteMode } from '@mui/material'
import { createAppTheme } from '../../theme/theme'

interface BrandingPreviewProps {
  mode: PaletteMode
  productName: string
  claim: string
  primaryColor: string
  /** Logo to show; either the stored one or an object URL of the file about to be uploaded. */
  logoUrl?: string
}

/**
 * One scheme's worth of live preview for the branding form (#583: "Live-Vorschau der Wirkung vor
 * dem Speichern"). Renders inside its own {@link ThemeProvider} built from the draft values, so
 * what is shown is produced by the same `createAppTheme` the application itself runs on - not by a
 * hand-painted mock-up that could agree with the form while disagreeing with the product.
 *
 * The form renders two of these side by side, light and dark. That is deliberate: an accent that
 * works on white can disappear against the navy surface, and the guidelines treat both schemes as
 * equally binding, so showing only the operator's currently active one would hide half the
 * consequence of their choice.
 */
export default function BrandingPreview({
  mode,
  productName,
  claim,
  primaryColor,
  logoUrl,
}: BrandingPreviewProps) {
  const theme = useMemo(() => createAppTheme(mode, { primaryColor }), [mode, primaryColor])

  return (
    <ThemeProvider theme={theme}>
      <Paper
        variant="outlined"
        // aria-hidden + inert: everything in here is a rendering of values the form already
        // exposes as labelled fields - announcing them a second time would add noise, not
        // information. inert also removes the preview's buttons from the tab order; without it,
        // keyboard users land on focus stops that screen readers cannot see (#956).
        aria-hidden
        inert
        sx={{
          p: 2.5,
          bgcolor: 'background.default',
          color: 'text.primary',
          display: 'flex',
          flexDirection: 'column',
          gap: 1.5,
          height: '100%',
        }}
      >
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
          {logoUrl && (
            <Box
              component="img"
              src={logoUrl}
              alt=""
              height={28}
              sx={{ height: 28, width: 'auto', maxWidth: 120, objectFit: 'contain' }}
            />
          )}
          <Typography variant="h6" sx={{ fontWeight: 700 }}>
            {productName}
          </Typography>
        </Box>
        {claim && (
          <Typography variant="caption" color="text.secondary">
            {claim}
          </Typography>
        )}
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
          <Button variant="contained" size="small">
            Fortfahren
          </Button>
          <Button variant="outlined" size="small">
            Abbrechen
          </Button>
          <Chip label="Fundstelle" size="small" color="primary" variant="outlined" />
        </Box>
      </Paper>
    </ThemeProvider>
  )
}
