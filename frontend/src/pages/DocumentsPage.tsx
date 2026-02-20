import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import DescriptionIcon from '@mui/icons-material/Description'

export default function DocumentsPage() {
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
      <DescriptionIcon sx={{ fontSize: 48 }} />
      <Typography variant="h5">Documents</Typography>
      <Typography variant="body1">Coming soon</Typography>
    </Box>
  )
}
