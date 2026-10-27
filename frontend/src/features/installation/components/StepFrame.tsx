import { useEffect, useId, useRef, type FormEvent, type ReactNode } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import { fontFamily } from '@/shared/theme/tokens'
import { stepMeta } from '../utils/steps'

/** The way out of a step besides submitting it. */
export interface StepNavigationProps {
  /** Back to the previous step without saving; absent on the first step. */
  onBack?: () => void
  /** Optional steps only: on to the next step without saving. */
  onSkip?: () => void
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
  /** The primary action refuses for now; `describedBy` names the element that says why. */
  unavailable?: boolean
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

  useEffect(() => {
    if (focusHeading) headingRef.current?.focus()
  }, [title, focusHeading])

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (!busy && !unavailable) onSubmit()
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
          color: 'text.secondary',
          mb: 0.75,
        }}
      >
        {stepMeta(mandatory, laterUnder)}
      </Typography>
      <Typography
        id={headingId}
        ref={headingRef}
        tabIndex={-1}
        component="h2"
        sx={{ fontSize: 22, fontWeight: 600, lineHeight: 1.3 }}
      >
        {title}
      </Typography>
      <Typography
        component="div"
        sx={{ fontSize: 14, color: 'text.secondary', mt: 1, mb: 3, maxWidth: '62ch' }}
      >
        {description}
      </Typography>

      {failure && (
        <Alert severity="error" sx={{ mb: 2.5 }}>
          {failure}
        </Alert>
      )}

      {/* Fields take the full width of the step, so they end on the right edge of the primary
          action below; the reading width comes from the layout's column. */}
      <Stack spacing={2.25}>{children}</Stack>

      <Box
        sx={{
          display: 'flex',
          flexWrap: 'wrap',
          alignItems: 'center',
          gap: 1.5,
          mt: 4,
          pt: 2,
          borderTop: 1,
          borderColor: 'divider',
        }}
      >
        {onBack && (
          <Button variant="outlined" onClick={onBack} disabled={busy}>
            Zurück
          </Button>
        )}
        <Box sx={{ flex: 1 }} />
        {onSkip && (
          <Button variant="text" onClick={onSkip} disabled={busy}>
            Überspringen
          </Button>
        )}
        <BusyButton
          type="submit"
          variant="contained"
          busy={busy}
          busyAnnouncement={busyAnnouncement}
          unavailable={unavailable}
          aria-describedby={describedBy}
        >
          {busy ? 'Wird geprüft …' : submitLabel}
        </BusyButton>
      </Box>
    </Box>
  )
}
