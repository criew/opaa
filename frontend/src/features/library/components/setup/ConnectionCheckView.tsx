import { useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'
import CheckIcon from '@mui/icons-material/Check'
import CloseIcon from '@mui/icons-material/Close'
import RemoveIcon from '@mui/icons-material/Remove'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import type { ConnectionCheck, StageState } from './connectionCheck'

const STAGE_ICON: Record<StageState, { Icon: typeof CheckIcon; color: string }> = {
  ok: { Icon: CheckIcon, color: 'success.main' },
  failed: { Icon: CloseIcon, color: 'error.main' },
  skipped: { Icon: RemoveIcon, color: 'text.disabled' },
  open: { Icon: RemoveIcon, color: 'text.disabled' },
}

const UNREADABLE_SHOWN = 5

/**
 * The one connection test of every connector: a secondary button and, under it, the result by
 * stage. The signal colour sits in the stage mark; the words carry the result.
 */
export default function ConnectionCheckView({
  check,
  id,
  allowAccept,
  onRun,
}: {
  check: ConnectionCheck
  id: string
  /** Starts the test; the setup first makes sure the connection fields are complete. */
  onRun: () => void
  /** Offer to go on despite a failed test (wizard and save). */
  allowAccept: boolean
}) {
  const [showAll, setShowAll] = useState(false)
  const { outcome, state } = check
  const unreadable = outcome?.unreadable ?? []
  const shown = showAll ? unreadable : unreadable.slice(0, UNREADABLE_SHOWN)
  return (
    <Box id={id} tabIndex={-1} sx={{ outline: 'none' }}>
      <BusyButton
        variant="outlined"
        size="small"
        busy={state === 'checking'}
        busyAnnouncement="Verbindung wird geprüft"
        onClick={onRun}
      >
        {state === 'checking' ? 'Wird geprüft …' : 'Verbindung testen'}
      </BusyButton>
      <Box role="status" aria-live="polite" sx={{ mt: outcome ? 1.5 : 0 }}>
        {outcome && (
          <>
            <Box
              component="ul"
              sx={{ listStyle: 'none', p: 0, m: 0, display: 'flex', flexWrap: 'wrap', columnGap: 2, rowGap: 0.5 }}
            >
              {outcome.stages.map((stage) => {
                const { Icon, color } = STAGE_ICON[stage.state]
                return (
                  <Box
                    component="li"
                    key={stage.label}
                    sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5, fontSize: 13 }}
                  >
                    <Icon aria-hidden sx={{ fontSize: 16, color }} />
                    <span>
                      {stage.label} {stage.text}
                    </span>
                  </Box>
                )
              })}
            </Box>
            <Typography sx={{ fontSize: 13, color: 'text.secondary', mt: 0.5 }}>
              {outcome.message}
            </Typography>
            {unreadable.length > 0 && (
              <Box component="ul" sx={{ m: 0, mt: 0.5, pl: 2.5, fontSize: 13 }}>
                {shown.map((name) => (
                  <li key={name}>
                    <Typography
                      component="span"
                      sx={{ fontSize: 13, overflowWrap: 'anywhere' }}
                    >
                      {name}
                    </Typography>
                  </li>
                ))}
              </Box>
            )}
            {unreadable.length > UNREADABLE_SHOWN && (
              <Button size="small" variant="text" onClick={() => setShowAll((s) => !s)}>
                {showAll ? 'Weniger anzeigen' : `${unreadable.length - UNREADABLE_SHOWN} weitere anzeigen`}
              </Button>
            )}
            {allowAccept && outcome.state === 'failed' && !check.accepted && (
              <Button size="small" variant="text" sx={{ mt: 0.5 }} onClick={check.accept}>
                Trotzdem fortfahren
              </Button>
            )}
            {check.accepted && outcome.state === 'failed' && (
              <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 0.5 }}>
                Sie fahren ohne erfolgreichen Test fort. Der erste Lauf meldet, was fehlt.
              </Typography>
            )}
          </>
        )}
      </Box>
    </Box>
  )
}
