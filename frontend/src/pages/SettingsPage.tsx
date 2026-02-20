import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import SettingsIcon from '@mui/icons-material/Settings'

export default function SettingsPage() {
  return (
    <Box
      sx={{
        flexGrow: 1,
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 2,
        color: 'text.secondary',
      }}
    >
      <SettingsIcon sx={{ fontSize: 48 }} />
      <Typography variant="h5">Settings</Typography>
      <Typography variant="body1">Coming soon</Typography>
    </Box>
  )
}
