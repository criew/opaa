import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { useBrandingStore } from '../stores/brandingStore'
import DemoNotice from './DemoNotice'

/** The version alone; the product name in front of it comes from the branding (#583). */
export const APP_VERSION = 'v0.1.0'

/** Slim `contentinfo` landmark; the visual treatment belongs to the shell redesign (#587). */
export default function AppFooter() {
  const productName = useBrandingStore((s) => s.branding.productName)

  return (
    <Box
      component="footer"
      sx={{
        px: 2.5,
        py: 1,
        borderTop: 1,
        borderColor: 'divider',
        bgcolor: 'background.paper',
        flexShrink: 0,
      }}
    >
      <Typography variant="caption" color="text.secondary">
        {productName} {APP_VERSION}
      </Typography>
      {/* Only rendered on demo instances (OPAA_DEMO_MODE), see DemoNotice.tsx / #230. */}
      <DemoNotice />
    </Box>
  )
}
