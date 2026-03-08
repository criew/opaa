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

export default function SettingsPage() {
  const themeMode = useUiStore((s) => s.themeMode)
  const setThemeMode = useUiStore((s) => s.setThemeMode)

  return (
    <Box sx={{ flexGrow: 1, p: 4, maxWidth: 600 }}>
      <Typography variant="h5" gutterBottom>
        Settings
      </Typography>

      <Paper variant="outlined" sx={{ p: 3, mt: 2 }}>
        <Typography variant="subtitle1" fontWeight="medium" gutterBottom>
          Appearance
        </Typography>
        <Typography variant="body2" color="text.secondary" mb={2}>
          Choose how OPAA looks to you.
        </Typography>
        <ToggleButtonGroup
          value={themeMode}
          exclusive
          onChange={(_e, value: ThemeMode | null) => {
            if (value !== null) setThemeMode(value)
          }}
          aria-label="theme mode"
        >
          <ToggleButton value="light" aria-label="light mode">
            <LightModeIcon sx={{ mr: 1 }} fontSize="small" />
            Light
          </ToggleButton>
          <ToggleButton value="system" aria-label="system default">
            <SettingsBrightnessIcon sx={{ mr: 1 }} fontSize="small" />
            System
          </ToggleButton>
          <ToggleButton value="dark" aria-label="dark mode">
            <DarkModeIcon sx={{ mr: 1 }} fontSize="small" />
            Dark
          </ToggleButton>
        </ToggleButtonGroup>
      </Paper>
    </Box>
  )
}
