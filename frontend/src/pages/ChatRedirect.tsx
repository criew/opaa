import { useEffect } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import CircularProgress from '@mui/material/CircularProgress'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { useNavigate } from 'react-router'
import { useSpaceStore } from '../stores/spaceStore'
import { usePageTitle } from '../hooks/usePageTitle'

/**
 * Resolves the global `/chat` entry point: it always lands on an empty, not-yet-persisted chat
 * ("new") in the user's default space, never on an existing conversation - continuing one is
 * always a deliberate click in the chat list. Instead of a never-resolving spinner, a failing or
 * empty space list yields a German message with a retry action.
 */
export default function ChatRedirect() {
  usePageTitle('Chat')
  const navigate = useNavigate()
  const spaces = useSpaceStore((s) => s.spaces)
  const isLoadingSpaces = useSpaceStore((s) => s.isLoadingList)
  const spacesError = useSpaceStore((s) => s.error)
  const loadSpaces = useSpaceStore((s) => s.loadSpaces)

  useEffect(() => {
    if (spaces.length === 0) {
      void loadSpaces()
    }
  }, [spaces.length, loadSpaces])

  const defaultSpace = spaces.find((space) => space.isDefault) ?? spaces[0]

  useEffect(() => {
    if (!defaultSpace) return
    navigate(`/spaces/${defaultSpace.id}/chats/new`, { replace: true })
  }, [defaultSpace, navigate])

  if (isLoadingSpaces && spaces.length === 0) {
    return (
      <Box sx={{ flexGrow: 1, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <CircularProgress />
      </Box>
    )
  }

  if (!defaultSpace) {
    return (
      <Box sx={{ flexGrow: 1, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <Stack spacing={2} sx={{ alignItems: 'center', textAlign: 'center', maxWidth: 400 }}>
          {spacesError ? (
            <Alert severity="error">{spacesError}</Alert>
          ) : (
            <Typography sx={{ color: 'text.secondary' }}>Kein Arbeitsraum verfügbar.</Typography>
          )}
          <Button variant="outlined" onClick={() => void loadSpaces()}>
            Erneut versuchen
          </Button>
        </Stack>
      </Box>
    )
  }

  return null
}
