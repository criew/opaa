import { useEffect } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import CircularProgress from '@mui/material/CircularProgress'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { useNavigate } from 'react-router'
import { useSpaceStore } from '../stores/spaceStore'
import { useChatListStore } from '../stores/chatListStore'

/**
 * Resolves the legacy `/chat` entry point (#527): lands on the user's default space and, within
 * it, the most recently used chat - or a not-yet-created chat ("new") if the space has none yet,
 * so there is never a dead end. Surfaces a German error with a retry action instead of a
 * never-resolving spinner if either the space list or the default space's chat list fails to load
 * (#548 review, nit c).
 */
export default function ChatRedirect() {
  const navigate = useNavigate()
  const spaces = useSpaceStore((s) => s.spaces)
  const isLoadingSpaces = useSpaceStore((s) => s.isLoadingList)
  const spacesError = useSpaceStore((s) => s.error)
  const loadSpaces = useSpaceStore((s) => s.loadSpaces)
  const chatsBySpaceId = useChatListStore((s) => s.chatsBySpaceId)
  const chatsError = useChatListStore((s) => s.error)
  const loadChats = useChatListStore((s) => s.loadChats)

  useEffect(() => {
    if (spaces.length === 0) {
      void loadSpaces()
    }
  }, [spaces.length, loadSpaces])

  const defaultSpace = spaces.find((space) => space.isDefault) ?? spaces[0]

  useEffect(() => {
    if (!defaultSpace) return
    if (chatsBySpaceId[defaultSpace.id] === undefined) {
      void loadChats(defaultSpace.id)
    }
  }, [defaultSpace, chatsBySpaceId, loadChats])

  useEffect(() => {
    if (!defaultSpace) return
    const chats = chatsBySpaceId[defaultSpace.id]
    if (chats === undefined) return
    const target = chats[0]?.id ?? 'new'
    navigate(`/spaces/${defaultSpace.id}/chats/${target}`, { replace: true })
  }, [defaultSpace, chatsBySpaceId, navigate])

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
            <Typography color="text.secondary">Kein Arbeitsraum verfügbar.</Typography>
          )}
          <Button variant="outlined" onClick={() => void loadSpaces()}>
            Erneut versuchen
          </Button>
        </Stack>
      </Box>
    )
  }

  const chats = chatsBySpaceId[defaultSpace.id]
  if (chats === undefined) {
    if (chatsError) {
      return (
        <Box sx={{ flexGrow: 1, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <Stack spacing={2} sx={{ alignItems: 'center', textAlign: 'center', maxWidth: 400 }}>
            <Alert severity="error">{chatsError}</Alert>
            <Button variant="outlined" onClick={() => void loadChats(defaultSpace.id)}>
              Erneut versuchen
            </Button>
          </Stack>
        </Box>
      )
    }
    return (
      <Box sx={{ flexGrow: 1, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <CircularProgress />
      </Box>
    )
  }

  return null
}
