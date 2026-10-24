import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'
import BusyButton from '@/shared/ui/a11y/BusyButton'

interface LibraryCreateFooterProps {
  activeStep: number
  stepCount: number
  submitting: boolean
  /** Grants noted in the sharing step, counted while that step is shown; 0 hides the count. */
  pendingGrantCount: number
  /** Why nothing can be created; it also describes the create button. */
  missingCapability: string | null
  /** The connection check „Weiter“ runs is in flight. */
  checking: boolean
  onCancel: () => void
  onBack: () => void
  onNext: () => void
  onCreate: () => void
}

/** The wizard's button row: cancel, back, next or create - never disabled for missing input. */
export default function LibraryCreateFooter({
  activeStep,
  stepCount,
  submitting,
  pendingGrantCount,
  missingCapability,
  checking,
  onCancel,
  onBack,
  onNext,
  onCreate,
}: LibraryCreateFooterProps) {
  return (
    <Box
      sx={{
        display: 'flex',
        alignItems: 'center',
        gap: 1.5,
        mt: 4,
        pt: 2,
        borderTop: 1,
        borderColor: 'divider',
      }}
    >
      <Button variant="text" onClick={onCancel} disabled={submitting}>
        Abbrechen
      </Button>
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
        <BusyButton
          variant="contained"
          onClick={onNext}
          busy={checking}
          busyAnnouncement="Verbindung wird getestet"
        >
          {checking ? 'Wird getestet …' : 'Weiter'}
        </BusyButton>
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
