import { createTheme } from '@mui/material/styles'

export const CHAT_MAX_WIDTH = '896px'

const theme = createTheme({
  palette: {
    mode: 'dark',
    primary: {
      main: '#137fec',
    },
    background: {
      default: '#101922',
      paper: '#1c2127',
    },
    divider: '#283039',
    text: {
      primary: '#e0e0e0',
      secondary: '#9e9e9e',
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
          backgroundColor: '#101922',
        },
      },
    },
  },
})

export default theme
