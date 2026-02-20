import IconButton from '@mui/material/IconButton'
import AdminPanelSettingsIcon from '@mui/icons-material/AdminPanelSettings'
import { useIndexingStore } from '../../stores/indexingStore'

export default function AdminDrawerToggle() {
  const toggleDrawer = useIndexingStore((s) => s.toggleDrawer)

  return (
    <IconButton onClick={toggleDrawer} aria-label="Toggle admin drawer" sx={{ ml: 'auto' }}>
      <AdminPanelSettingsIcon />
    </IconButton>
  )
}
