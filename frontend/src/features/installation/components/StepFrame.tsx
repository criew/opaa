import { useEffect, useId, useRef, useState, type FormEvent, type ReactNode } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import { fontFamily } from '@/shared/theme/tokens'
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
  /** The primary action refuses for now. */
  unavailable?: boolean
  /** The element that describes the primary action, e.g. why it refuses or what it will do. */
  describedBy?: string
  /** Moves the focus to the heading when the step opens; off for the page's first view. */
  focusHeading?: boolean
  onSubmit: () => void
  children: ReactNode
}

/**
 * The frame of every step: the step's heading (an h2 under the page's h1, focused when the step
 * opens so a screen reader announces it), the sentence about the step, its fields and the one
 * primary action at the end. Enter in any field submits, like the action.
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
  unavailable = false,
  describedBy,
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
    if (!working && !unavailable) {
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
      <Typography
        sx={{
          fontFamily: fontFamily.mono,
          fontSize: 11,
          fontWeight: 500,
          letterSpacing: '0.08em',
          textTransform: 'uppercase',
          // Eyebrow above a heading in accent (guidelines 3.3); the step's state stays in words.
          color: 'primary.main',
          mb: STEP_RHYTHM.withinHeading,
        }}
      >
        {stepMeta(mandatory, laterUnder)}
      </Typography>
      <Typography id={headingId} ref={headingRef} tabIndex={-1} component="h2" sx={STEP_TITLE_SX}>
        {title}
      </Typography>
      <Typography
        component="div"
        sx={{
          fontSize: 14,
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
          alignItems: 'center',
          gap: 1.5,
          mt: STEP_RHYTHM.toActions,
          pt: STEP_RHYTHM.actionsDivider,
          borderTop: 1,
          borderColor: 'divider',
        }}
      >
        {onBack && (
          <Button variant="outlined" onClick={onBack} disabled={working}>
            Zurück
          </Button>
        )}
        {/* The forward actions keep to the right edge, also when they wrap below the back button. */}
        <Box
          sx={{
            display: 'flex',
            flexWrap: 'wrap',
            justifyContent: 'flex-end',
            alignItems: 'center',
            gap: 1.5,
            ml: 'auto',
          }}
        >
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
            unavailable={unavailable || skipping}
            aria-describedby={describedBy}
          >
            {busy ? 'Wird geprüft …' : submitLabel}
          </BusyButton>
        </Box>
      </Box>
    </Box>
  )
}
