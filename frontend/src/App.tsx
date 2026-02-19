import { CssBaseline, ThemeProvider, createTheme } from '@mui/material'
import Box from '@mui/material/Box'
import Container from '@mui/material/Container'
import Typography from '@mui/material/Typography'

const theme = createTheme()

function App() {
  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <Container maxWidth="sm">
        <Box
          sx={{
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            justifyContent: 'center',
            minHeight: '100vh',
          }}
        >
          <Typography variant="h2" component="h1" gutterBottom>
            OPAA
          </Typography>
          <Typography variant="subtitle1" color="text.secondary">
            Open Project AI Assistant
          </Typography>
        </Box>
      </Container>
    </ThemeProvider>
  )
}

export default App
