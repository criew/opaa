import { createTheme } from '@mui/material/styles'
import type { PaletteMode } from '@mui/material'

export const CHAT_MAX_WIDTH = '896px'

export function createAppTheme(mode: PaletteMode) {
  const isDark = mode === 'dark'
  return createTheme({
    palette: {
      mode,
      primary: {
        main: '#137fec',
      },
      background: {
        default: isDark ? '#101922' : '#f5f7fa',
        paper: isDark ? '#1c2127' : '#ffffff',
      },
      divider: isDark ? '#283039' : '#e0e0e0',
      text: {
        primary: isDark ? '#e0e0e0' : '#1a1a1a',
        secondary: isDark ? '#9e9e9e' : '#616161',
      },
    },
    typography: {
      fontFamily: '"Inter", "Roboto", "Helvetica", "Arial", sans-serif',
    },
    shape: {
      borderRadius: 8,
    },
    components: {
      MuiCssBaseline: {
        styleOverrides: {
          body: {
            backgroundColor: isDark ? '#101922' : '#f5f7fa',
          },
        },
      },
    },
  })
}
