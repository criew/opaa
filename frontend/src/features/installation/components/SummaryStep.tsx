import { useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Link from '@mui/material/Link'
import Typography from '@mui/material/Typography'
import StateDot from '@/shared/ui/StateDot'
import type { InstallationStatusResponse, InstallationStepId } from '../api/types'
import type { WizardResult } from '../hooks/useInstallationWizard'
import {
  STEP_INFO,
  STEP_STATUS_LABEL,
  STEP_STATUS_TONE,
  SUMMARY_LABEL,
  openSteps,
  stepStatus,
} from '../utils/steps'
import StepFrame, { type StepNavigationProps } from './StepFrame'
import { fontSize } from '@/shared/theme/tokens'

interface SummaryStepProps extends Pick<StepNavigationProps, 'onBack'> {
  installation: InstallationStatusResponse
  complete: () => Promise<WizardResult>
  /** Opens a step from its row or from the notice about what is still open. */
  onSelect: (step: InstallationStepId) => void
}

const STEP_LINK_SX = { verticalAlign: 'baseline', fontSize: 'inherit' } as const

/**
 * Every step with its state as a link to the step, and the completion. Whether it can complete is
 * the API's verdict (`completable`). „Installation abschließen“ is always active: while a step
 * holds the completion up, it names that step in a notice and leads there. A checked mail step
 * also shows the backend's sentence about its check, which says whether a test message went out.
 */
export default function SummaryStep({
  installation,
  complete,
  onBack,
  onSelect,
}: SummaryStepProps) {
  const noticeRef = useRef<HTMLDivElement>(null)
  const [failure, setFailure] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [asked, setAsked] = useState(false)
  const open = openSteps(installation)
  const blocked = !installation.completable

  async function handleSubmit() {
    if (blocked) {
      setAsked(true)
      requestAnimationFrame(() => noticeRef.current?.focus())
      return
    }
    setBusy(true)
    setFailure(null)
    const result = await complete()
    setBusy(false)
    if (!result.ok) setFailure(result.failure.message)
  }

  return (
    <StepFrame
      title={SUMMARY_LABEL}
      mandatory
      description={
        blocked
          ? 'Abschließen lässt sich die Installation, sobald alle Pflichtschritte geprüft sind und jeder ausgefüllte optionale Schritt geprüft oder übersprungen ist.'
          : 'Der Abschluss legt an, was die geprüften Schritte festlegen, und beendet die Einrichtung. Danach melden Sie sich mit dem ersten Konto an; das Token verliert seine Gültigkeit.'
      }
      failure={failure}
      busy={busy}
      submitLabel="Installation abschließen"
      busyAnnouncement="Installation wird abgeschlossen"
      onSubmit={() => void handleSubmit()}
      onBack={onBack}
    >
      {blocked && asked && (
        <Alert severity="warning" role="alert" tabIndex={-1} ref={noticeRef}>
          {open.length === 0
            ? 'Die Installation lässt sich noch nicht abschließen; der Stand der Schritte steht unten.'
            : 'Noch nicht geprüft: '}
          {open.map((s, index) => (
            <span key={s.step}>
              {index > 0 && ', '}
              <Link
                component="button"
                type="button"
                onClick={() => onSelect(s.step)}
                sx={STEP_LINK_SX}
              >
                {STEP_INFO[s.step].label}
              </Link>
            </span>
          ))}
          {open.length > 0 && '. Der Name führt zum Schritt.'}
        </Alert>
      )}
      <Box component="ul" aria-label="Stand der Schritte" sx={{ m: 0, p: 0 }}>
        {installation.steps.map((s) => {
          const status = stepStatus(s)
          return (
            <Box
              component="li"
              key={s.step}
              sx={{
                listStyle: 'none',
                display: 'flex',
                justifyContent: 'space-between',
                gap: 2,
                py: 1.5,
                borderBottom: 1,
                borderColor: 'divider',
                // The action bar's divider closes the list; a second rule would double it.
                '&:last-of-type': { borderBottom: 0 },
              }}
            >
              <Box>
                <Typography sx={{ fontSize: fontSize.text }}>
                  <Link
                    component="button"
                    type="button"
                    onClick={() => onSelect(s.step)}
                    sx={STEP_LINK_SX}
                  >
                    {STEP_INFO[s.step].label}
                  </Link>
                  <Typography
                    component="span"
                    sx={{ fontSize: fontSize.meta, color: 'text.secondary' }}
                  >
                    {s.mandatory ? ' · Pflicht' : ' · optional'}
                  </Typography>
                </Typography>
                {s.step === 'MAIL' && s.checked && s.checkMessage && (
                  <Typography sx={{ fontSize: fontSize.text, color: 'text.secondary', mt: 0.5 }}>
                    {s.checkMessage}
                  </Typography>
                )}
              </Box>
              <StateDot tone={STEP_STATUS_TONE[status]} label={STEP_STATUS_LABEL[status]} />
            </Box>
          )
        })}
      </Box>
    </StepFrame>
  )
}
