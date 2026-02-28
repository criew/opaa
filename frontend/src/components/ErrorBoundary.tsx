import { Component, type ErrorInfo, type ReactNode } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import Button from '@mui/material/Button'
import Collapse from '@mui/material/Collapse'
import ErrorOutlineIcon from '@mui/icons-material/ErrorOutline'

interface Props {
  children: ReactNode
}

interface State {
  hasError: boolean
  error: Error | null
  showDetails: boolean
}

export default class ErrorBoundary extends Component<Props, State> {
  constructor(props: Props) {
    super(props)
    this.state = { hasError: false, error: null, showDetails: false }
  }

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error, showDetails: false }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('ErrorBoundary caught:', error, info)
  }

  render() {
    if (this.state.hasError) {
      const { error, showDetails } = this.state

      return (
        <Box
          sx={{
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            justifyContent: 'center',
            minHeight: '100vh',
            gap: 2,
            p: 4,
          }}
        >
          <ErrorOutlineIcon sx={{ fontSize: 64 }} color="error" />
          <Typography variant="h5">Something went wrong</Typography>
          <Typography variant="body1" color="text.secondary" textAlign="center">
            An unexpected error occurred. Please try reloading the page.
          </Typography>
          <Box sx={{ display: 'flex', gap: 2 }}>
            <Button variant="contained" onClick={() => window.location.reload()}>
              Reload
            </Button>
            <Button
              variant="outlined"
              onClick={() => this.setState((prev) => ({ showDetails: !prev.showDetails }))}
            >
              {showDetails ? 'Hide Details' : 'Show Details'}
            </Button>
          </Box>
          <Collapse in={showDetails}>
            <Box
              sx={{
                mt: 2,
                p: 2,
                maxWidth: 600,
                width: '100%',
                bgcolor: 'grey.100',
                borderRadius: 1,
                overflow: 'auto',
              }}
            >
              <Typography variant="subtitle2" color="error" gutterBottom>
                {error?.message}
              </Typography>
              {error?.stack && (
                <Typography
                  variant="body2"
                  component="pre"
                  sx={{
                    whiteSpace: 'pre-wrap',
                    wordBreak: 'break-word',
                    fontSize: '0.75rem',
                    color: 'text.secondary',
                    m: 0,
                  }}
                >
                  {error.stack}
                </Typography>
              )}
            </Box>
          </Collapse>
        </Box>
      )
    }

    return this.props.children
  }
}
