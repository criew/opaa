import { useEffect, useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'
import type { LocalUserChatCleanupResponse, LocalUserResponse } from '@/shared/api/types'
import { notify } from '@/shared/stores/notificationStore'
import {
  getLocalUserChatCleanup,
  holdLocalUserChatCleanup,
  resumeLocalUserChatCleanup,
} from '../api/localUserApi'
import { chatCleanupText, localUserErrorMessage } from './localUserLabels'

export const CHAT_CLEANUP_HEADING = 'Chats nach der Sperre'

interface LocalUserChatCleanupSectionProps {
  user: LocalUserResponse
}

/**
 * The deletion of the account's chats after a lock by the administration, in the single view of the
 * account only – never in the list. „Löschung anhalten" keeps the chats for a matter outside OPAA
 * until „Löschung fortsetzen"; both are audited without a reason.
 */
export default function LocalUserChatCleanupSection({ user }: LocalUserChatCleanupSectionProps) {
  const [state, setState] = useState<LocalUserChatCleanupResponse | null>(null)
  const [failed, setFailed] = useState(false)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let active = true
    getLocalUserChatCleanup(user.id)
      .then((loaded) => {
        if (active) setState(loaded)
      })
      .catch(() => {
        if (active) setFailed(true)
      })
    return () => {
      active = false
    }
  }, [user.id])

  async function toggle() {
    if (!state) return
    setBusy(true)
    try {
      const next = state.held
        ? await resumeLocalUserChatCleanup(user.id)
        : await holdLocalUserChatCleanup(user.id)
      setState(next)
      notify(
        next.held
          ? `Die Löschung der Chats von „${user.displayName}“ ist angehalten.`
          : `Die Löschung der Chats von „${user.displayName}“ wird fortgesetzt.`,
        'success',
      )
    } catch (err) {
      notify(localUserErrorMessage(err, 'Die Änderung ist fehlgeschlagen.'), 'error')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Box component="section" aria-labelledby="user-form-chat-cleanup-heading">
      <Typography
        id="user-form-chat-cleanup-heading"
        component="h3"
        sx={{ fontSize: 12, fontWeight: 600, mb: '5px' }}
      >
        {CHAT_CLEANUP_HEADING}
      </Typography>
      {failed && (
        <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
          Der Stand der Löschung konnte nicht geladen werden.
        </Typography>
      )}
      {state && (
        <>
          <Typography sx={{ fontSize: 13, color: 'text.secondary', mb: 1 }}>
            {chatCleanupText(state)}
          </Typography>
          {state.status !== 'SWITCHED_OFF' || state.held ? (
            <Button size="small" variant="outlined" onClick={() => void toggle()} disabled={busy}>
              {state.held ? 'Löschung fortsetzen' : 'Löschung anhalten'}
            </Button>
          ) : null}
        </>
      )}
    </Box>
  )
}
