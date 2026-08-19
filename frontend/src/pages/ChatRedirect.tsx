import { useEffect } from 'react'
import Box from '@mui/material/Box'
import CircularProgress from '@mui/material/CircularProgress'
import { useNavigate } from 'react-router'
import { useSpaceStore } from '../stores/spaceStore'
import { useChatListStore } from '../stores/chatListStore'

/**
 * Resolves the legacy `/chat` entry point (#527): lands on the user's default space and, within
 * it, the most recently used chat - or a not-yet-created chat ("new") if the space has none yet,
 * so there is never a dead end.
 */
export default function ChatRedirect() {
  const navigate = useNavigate()
  const spaces = useSpaceStore((s) => s.spaces)
  const isLoadingSpaces = useSpaceStore((s) => s.isLoadingList)
  const loadSpaces = useSpaceStore((s) => s.loadSpaces)
  const chatsBySpaceId = useChatListStore((s) => s.chatsBySpaceId)
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

  return (
    <Box sx={{ flexGrow: 1, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
      {isLoadingSpaces || !defaultSpace ? <CircularProgress /> : null}
    </Box>
  )
}
