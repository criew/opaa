import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'

export const APP_VERSION = 'OPAA v0.1.0'

/** Slim `contentinfo` landmark; the visual treatment belongs to the shell redesign (#587). */
export default function AppFooter() {
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
        {APP_VERSION}
      </Typography>
    </Box>
  )
}
