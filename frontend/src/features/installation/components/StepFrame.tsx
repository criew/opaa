import { useEffect, useId, useRef, useState, type FormEvent, type ReactNode } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import IconButton from '@mui/material/IconButton'
import Tooltip from '@mui/material/Tooltip'
import HelpOutlineIcon from '@mui/icons-material/HelpOutlineOutlined'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import { fontFamily, fontSize } from '@/shared/theme/tokens'
import { handbookPageUrl } from '@/shared/utils/handbook'
import type { WizardResult } from '../hooks/useInstallationWizard'
import { stepMeta } from '../utils/steps'
import { STEP_RHYTHM, STEP_TITLE_SX } from './stepLayout'

/** The way out of a step besides submitting it. */
export interface StepNavigationProps {
  /** Back to the previous step without saving; absent on the first step. */
  onBack?: () => void
  /**
   * Optional steps only: skips the step through the API. While it runs the step refuses further
   * actions; a failure stays on the step with its sentence, like a failed check.
   */
  onSkip?: () => Promise<WizardResult>
}

interface StepFrameProps extends StepNavigationProps {
  title: string
  mandatory: boolean
  /** Where the value can be changed after the installation. */
  laterUnder?: string | null
  description: ReactNode
  /** The sentence of a failed save or check; announced when it appears. */
  failure?: string | null
  busy?: boolean
  /** Label of the primary action; the steps with a check say what happens. */
  submitLabel?: string
  busyAnnouncement?: string
  /** Moves the focus to the heading when the step opens; off for the page's first view. */
  focusHeading?: boolean
  onSubmit: () => void
  children: ReactNode
}

/** Where the handbook explains the assistant and each of its steps. */
const HANDBOOK_PAGE = 'betrieb/installation.html#einrichtungs-assistent-im-browser'

/**
 * The frame of every step: the step's heading (an h2 under the page's h1, focused when the step
 * opens so a screen reader announces it), at most two sentences about the step with the handbook
 * behind „?“, its fields, and the actions on the right: „Zurück“, on optional steps
 * „Überspringen“, and the primary action. The primary action is always active; it refuses only
 * while a request of the step runs. Enter in any field submits, like the action.
 */
export default function StepFrame({
  title,
  mandatory,
  laterUnder,
  description,
  failure,
  busy = false,
  submitLabel = 'Prüfen und weiter',
  busyAnnouncement = 'Angaben werden gespeichert und geprüft',
  focusHeading = true,
  onSubmit,
  onBack,
  onSkip,
  children,
}: StepFrameProps) {
  const headingId = useId()
  const headingRef = useRef<HTMLHeadingElement>(null)
  const [skipping, setSkipping] = useState(false)
  const [skipFailure, setSkipFailure] = useState<string | null>(null)
  const working = busy || skipping
  const shownFailure = skipFailure ?? failure

  useEffect(() => {
    if (focusHeading) headingRef.current?.focus()
  }, [title, focusHeading])

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (!working) {
      setSkipFailure(null)
      onSubmit()
    }
  }

  async function handleSkip() {
    if (!onSkip || working) return
    setSkipping(true)
    setSkipFailure(null)
    const result = await onSkip()
    setSkipping(false)
    if (!result.ok) setSkipFailure(result.failure.message || null)
  }

  return (
    <Box component="form" aria-labelledby={headingId} onSubmit={handleSubmit} noValidate>
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 1,
          mb: STEP_RHYTHM.withinHeading,
        }}
      >
        <Typography
          sx={{
            fontFamily: fontFamily.mono,
            fontSize: fontSize.label,
            fontWeight: 500,
            letterSpacing: '0.08em',
            textTransform: 'uppercase',
            // Eyebrow above a heading in accent (guidelines 3.3); the step's state stays in words.
            color: 'primary.main',
          }}
        >
          {stepMeta(mandatory, laterUnder)}
        </Typography>
        <Tooltip title="Handbuch">
          <IconButton
            component="a"
            size="small"
            href={handbookPageUrl(HANDBOOK_PAGE, __OPAA_VERSION__)}
            target="_blank"
            rel="noopener noreferrer"
            aria-label="Handbuch zu diesem Schritt (öffnet in neuem Fenster)"
          >
            <HelpOutlineIcon fontSize="small" />
          </IconButton>
        </Tooltip>
      </Box>
      <Typography id={headingId} ref={headingRef} tabIndex={-1} component="h2" sx={STEP_TITLE_SX}>
        {title}
      </Typography>
      <Typography
        component="div"
        sx={{
          fontSize: fontSize.text,
          color: 'text.secondary',
          mt: STEP_RHYTHM.withinHeading,
          mb: STEP_RHYTHM.betweenGroups,
          maxWidth: '62ch',
        }}
      >
        {description}
      </Typography>

      {shownFailure && (
        <Alert severity="error" sx={{ mb: STEP_RHYTHM.betweenGroups }}>
          {shownFailure}
        </Alert>
      )}

      {/* Fields take the full width of the step, so they end on the right edge of the primary
          action below; the layout's column sets that width. */}
      <Stack spacing={STEP_RHYTHM.betweenGroups}>{children}</Stack>

      <Box
        sx={{
          display: 'flex',
          flexWrap: 'wrap',
          justifyContent: 'flex-end',
          alignItems: 'center',
          gap: 1.5,
          mt: STEP_RHYTHM.toActions,
          pt: STEP_RHYTHM.actionsDivider,
          borderTop: 1,
          borderColor: 'divider',
        }}
      >
        {onBack && (
          <Button variant="outlined" onClick={() => !working && onBack()}>
            Zurück
          </Button>
        )}
        {onSkip && (
          <BusyButton
            variant="text"
            onClick={() => void handleSkip()}
            busy={skipping}
            busyAnnouncement="Schritt wird übersprungen"
            unavailable={busy}
          >
            Überspringen
          </BusyButton>
        )}
        <BusyButton
          type="submit"
          variant="contained"
          busy={busy}
          busyAnnouncement={busyAnnouncement}
          unavailable={skipping}
        >
          {busy ? 'Wird geprüft …' : submitLabel}
        </BusyButton>
      </Box>
    </Box>
  )
}
