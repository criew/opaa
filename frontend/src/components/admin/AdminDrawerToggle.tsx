import IconButton from '@mui/material/IconButton'
import AdminPanelSettingsIcon from '@mui/icons-material/AdminPanelSettings'
import { useIndexingStore } from '../../stores/indexingStore'

export default function AdminDrawerToggle() {
  const toggleDrawer = useIndexingStore((s) => s.toggleDrawer)

  return (
    <IconButton
      onClick={toggleDrawer}
      aria-label="Admin-Bereich ein- oder ausblenden"
      sx={{ ml: 'auto' }}
    >
      <AdminPanelSettingsIcon />
    </IconButton>
  )
}
