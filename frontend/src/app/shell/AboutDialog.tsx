import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Typography from '@mui/material/Typography'
import { useBrandingStore } from '@/shared/branding/brandingStore'
import DemoNotice from './DemoNotice'

/**
 * The version alone, as the build was given it (vite.config.ts); the product name in front of it
 * comes from the branding.
 */
export const APP_VERSION = `v${__OPAA_VERSION__}`

interface AboutDialogProps {
  open: boolean
  onClose: () => void
}

/**
 * "Info zu OPAA": product name, version and - on a demo instance - the demo notice. Every value
 * comes from the frontend itself; there is no build-info endpoint behind this.
 */
export default function AboutDialog({ open, onClose }: AboutDialogProps) {
  const productName = useBrandingStore((s) => s.branding.productName)

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Info zu {productName}</DialogTitle>
      <DialogContent>
        <Typography>
          {productName} {APP_VERSION}
        </Typography>
        {/* Only rendered on demo instances (OPAA_DEMO_MODE), see DemoNotice.tsx. */}
        <DemoNotice />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Schließen</Button>
      </DialogActions>
    </Dialog>
  )
}
