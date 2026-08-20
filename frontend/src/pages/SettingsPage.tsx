import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import Paper from '@mui/material/Paper'
import ToggleButton from '@mui/material/ToggleButton'
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup'
import DarkModeIcon from '@mui/icons-material/DarkMode'
import LightModeIcon from '@mui/icons-material/LightMode'
import SettingsBrightnessIcon from '@mui/icons-material/SettingsBrightness'
import { useUiStore } from '../stores/uiStore'
import type { ThemeMode } from '../stores/uiStore'
import PageHeading from '../components/a11y/PageHeading'

export default function SettingsPage() {
  const themeMode = useUiStore((s) => s.themeMode)
  const setThemeMode = useUiStore((s) => s.setThemeMode)

  return (
    <Box sx={{ flexGrow: 1, p: 4, maxWidth: 600 }}>
      <PageHeading title="Einstellungen" gutterBottom />

      <Paper variant="outlined" sx={{ p: 3, mt: 2 }}>
        <Typography variant="subtitle1" gutterBottom sx={{ fontWeight: 'medium' }}>
          Darstellung
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          Legen Sie fest, wie OPAA für Sie aussieht.
        </Typography>
        <ToggleButtonGroup
          value={themeMode}
          exclusive
          onChange={(_e, value: ThemeMode | null) => {
            if (value !== null) setThemeMode(value)
          }}
          aria-label="Farbschema"
        >
          <ToggleButton value="light" aria-label="Helles Farbschema">
            <LightModeIcon sx={{ mr: 1 }} fontSize="small" />
            Hell
          </ToggleButton>
          <ToggleButton value="system" aria-label="Systemvorgabe">
            <SettingsBrightnessIcon sx={{ mr: 1 }} fontSize="small" />
            System
          </ToggleButton>
          <ToggleButton value="dark" aria-label="Dunkles Farbschema">
            <DarkModeIcon sx={{ mr: 1 }} fontSize="small" />
            Dunkel
          </ToggleButton>
        </ToggleButtonGroup>
      </Paper>
    </Box>
  )
}
