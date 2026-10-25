import type { Ref } from 'react'
import { Link as RouterLink } from 'react-router'
import Box from '@mui/material/Box'
import Link from '@mui/material/Link'
import type { StatusTone } from '@/shared/ui/StatusLine'
import { stateTargetHref, type LibraryState } from './libraryState'

const TONE_COLOR: Record<StatusTone, string> = {
  neutral: 'text.disabled',
  success: 'success.main',
  warning: 'warning.main',
  error: 'error.main',
}

/**
 * The library's state in the head: the signal colour only in the dot, the word in the running
 * text colour and a link to where its cause lives. A ready state („Aktuell“) carries no dot
 * (guidelines 5.5); a running one no colour.
 */
export default function LibraryStateLink({
  state,
  focusRef,
}: {
  state: LibraryState
  /** Takes the focus when the state appears after an action, e.g. an erasure was marked. */
  focusRef?: Ref<HTMLDivElement>
}) {
  const quiet = state.tone === 'success'
  const word = (
    <Box
      component="span"
      sx={{ color: quiet ? 'text.secondary' : 'text.primary', fontWeight: 500 }}
    >
      {state.word}
    </Box>
  )
  return (
    <Box
      component="span"
      ref={focusRef}
      tabIndex={-1}
      role="status"
      data-testid="library-state"
      sx={{ display: 'inline-flex', alignItems: 'baseline', gap: 0.75, outline: 'none' }}
    >
      {!quiet && (
        <Box
          component="span"
          aria-hidden
          sx={{
            width: 8,
            height: 8,
            borderRadius: '50%',
            flex: 'none',
            alignSelf: 'center',
            bgcolor: TONE_COLOR[state.tone],
          }}
        />
      )}
      {state.target ? (
        <Link
          component={RouterLink}
          to={stateTargetHref(state.target)}
          replace
          underline="hover"
          color="inherit"
        >
          {word}
        </Link>
      ) : (
        word
      )}
      {state.detail && <span>· {state.detail}</span>}
    </Box>
  )
}
