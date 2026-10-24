import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Link from '@mui/material/Link'
import { TEXT_LINK } from './setup/rowStyles'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import Typography from '@mui/material/Typography'

interface LibraryCreateFooterProps {
  activeStep: number
  stepCount: number
  submitting: boolean
  /** Grants noted in the sharing step, counted while that step is shown; 0 hides the count. */
  pendingGrantCount: number
  /** Why nothing can be created; it also describes the create button. */
  missingCapability: string | null
  /** „Ändern“ on the review step led here: the next step is the review again. */
  returnToReview: boolean
  onCancel: () => void
  onBack: () => void
  onNext: () => void
  onCreate: () => void
  /** A line above the buttons; off where the content ends in a line of its own. */
  separated?: boolean
}

/**
 * The wizard's button row: cancel, back, next or create. Next and create are never disabled: a
 * missing answer is reported where it is missing, and a running creation swallows a second click.
 */
export default function LibraryCreateFooter({
  activeStep,
  stepCount,
  submitting,
  pendingGrantCount,
  missingCapability,
  returnToReview,
  onCancel,
  onBack,
  onNext,
  onCreate,
  separated = true,
}: LibraryCreateFooterProps) {
  return (
    <Box
      sx={{
        display: 'flex',
        alignItems: 'center',
        gap: 1.5,
        mt: separated ? 4 : 2,
        pt: separated ? 2 : 0,
        borderTop: separated ? 1 : 0,
        borderColor: 'divider',
      }}
    >
      <Link
        component="button"
        type="button"
        underline="hover"
        onClick={onCancel}
        disabled={submitting}
        sx={TEXT_LINK}
      >
        Abbrechen
      </Link>
      <Box sx={{ flex: 1 }} />
      {pendingGrantCount > 0 && (
        <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>
          {pendingGrantCount === 1
            ? '1 Freigabe vorgemerkt'
            : `${pendingGrantCount} Freigaben vorgemerkt`}
        </Typography>
      )}
      {activeStep > 0 && (
        <Button variant="outlined" onClick={onBack} disabled={submitting}>
          Zurück
        </Button>
      )}
      {activeStep < stepCount - 1 ? (
        <Button variant="contained" onClick={onNext}>
          {returnToReview ? 'Zur Prüfung' : 'Weiter'}
        </Button>
      ) : (
        <BusyButton
          variant="contained"
          onClick={onCreate}
          busy={submitting}
          busyAnnouncement="Bibliothek wird angelegt"
          aria-describedby={
            missingCapability !== null ? 'library-create-capability-hint' : undefined
          }
        >
          {submitting ? 'Wird angelegt …' : 'Bibliothek anlegen'}
        </BusyButton>
      )}
    </Box>
  )
}
