import { useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Link from '@mui/material/Link'
import Typography from '@mui/material/Typography'
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined'
import { isDemoModeEnabled } from '../utils/runtimeConfig'

const LHM_CORPUS_URL = 'https://huggingface.co/datasets/it-at-m/LHM-Dienstleistungen-Corpus'
const LHM_CORPUS_LICENSE_URL =
  'https://github.com/criew/opaa/blob/main/demo/corpus/THIRD-PARTY-LICENSES/LHM-Dienstleistungen-Corpus-MIT.txt'

/**
 * Demo/source notice (#230): only ever rendered when the frontend container's `OPAA_DEMO_MODE`
 * flag is on (see runtimeConfig.ts) - this notice belongs on the demo instance, not on every
 * OPAA installation.
 *
 * Sits in AppFooter, which stays visible without scrolling (AppShell.tsx renders the footer in a
 * `height: '100vh'` flex column, and AppFooter's outer Box sets `flexShrink: 0` so it never gets
 * squeezed out by scrollable content above it). The demo-character hint itself is always-visible
 * text, satisfying the "sichtbar" acceptance criterion directly; the source/licensing details
 * (dataset origin, MIT license, provenance URL) sit behind the "Quellen & Lizenz" link so the
 * footer stays short - the link itself is reachable without scrolling, satisfying "ohne Scrollen
 * erreichbar".
 */
export default function DemoNotice() {
  const [open, setOpen] = useState(false)

  if (!isDemoModeEnabled()) {
    return null
  }

  return (
    <>
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          gap: 0.5,
          mt: 0.25,
        }}
      >
        <InfoOutlinedIcon fontSize="inherit" color="action" aria-hidden="true" />
        <Typography variant="caption" color="text.secondary">
          Demo-Instanz mit synthetischen Inhalten der fiktiven Stadt Rheinfurt — keine
          Faktenautorität.{' '}
          <Link component="button" type="button" onClick={() => setOpen(true)}>
            Quellen &amp; Lizenz
          </Link>
        </Typography>
      </Box>

      <Dialog open={open} onClose={() => setOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle>Quellen und Demo-Charakter</DialogTitle>
        <DialogContent>
          <Typography gutterBottom>
            Rheinfurt ist eine erfundene Stadt. Alle Texte dieser Instanz sind synthetisch
            formuliert und dienen ausschließlich der Vorführung — sie sind keine echte Behörde und
            keine verlässliche Quelle für tatsächliche Verwaltungsauskünfte.
          </Typography>
          <Typography>
            Als Rohmaterial dient der{' '}
            <Link href={LHM_CORPUS_URL} target="_blank" rel="noopener noreferrer">
              LHM-Dienstleistungen-Corpus
            </Link>{' '}
            der Landeshauptstadt München (MIT-Lizenz), dessen Leistungsbeschreibungen automatisiert
            auf die fiktive Stadt Rheinfurt umgeschrieben wurden.
          </Typography>
          <Typography>
            Vollständiger Lizenztext:{' '}
            <Link href={LHM_CORPUS_LICENSE_URL} target="_blank" rel="noopener noreferrer">
              LHM-Dienstleistungen-Corpus-MIT.txt
            </Link>
            .
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setOpen(false)}>Schließen</Button>
        </DialogActions>
      </Dialog>
    </>
  )
}
