import { useId, useState } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import StateDot from '@/shared/ui/StateDot'
import type { InstallationState } from '../api/types'
import type { WizardResult } from '../hooks/useInstallationWizard'
import {
  STEP_INFO,
  STEP_STATUS_LABEL,
  SUMMARY_LABEL,
  openRequiredSteps,
  stepStatus,
} from '../utils/steps'
import StepFrame, { type StepNavigationProps } from './StepFrame'

interface SummaryStepProps extends Pick<StepNavigationProps, 'onBack'> {
  installation: InstallationState
  complete: () => Promise<WizardResult>
}

const TONE = { checked: 'success', unchecked: 'warning', open: 'neutral' } as const

/**
 * Every step with its state, and the completion. The action stays visible but refuses while a
 * required step is not checked, and the sentence above it names which ones - a hidden or merely
 * greyed-out button would leave the reason to guesswork.
 */
export default function SummaryStep({ installation, complete, onBack }: SummaryStepProps) {
  const hintId = useId()
  const [failure, setFailure] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const open = openRequiredSteps(installation)

  async function handleSubmit() {
    if (open.length > 0) return
    setBusy(true)
    setFailure(null)
    const result = await complete()
    setBusy(false)
    if (!result.ok) setFailure(result.failure.message)
  }

  return (
    <StepFrame
      title={SUMMARY_LABEL}
      required
      description={
        open.length > 0 ? (
          <span id={hintId}>
            Abschließen lässt sich die Installation erst, wenn alle Pflichtschritte geprüft sind.
            Offen: {open.map((s) => STEP_INFO[s.step].label).join(', ')}.
          </span>
        ) : (
          'Alle Pflichtschritte sind geprüft. Der Abschluss legt die Einstellungen an und schließt die Einrichtung; danach melden Sie sich mit dem ersten Konto an. Das Token verliert seine Gültigkeit.'
        )
      }
      failure={failure}
      busy={busy}
      submitLabel="Installation abschließen"
      busyAnnouncement="Installation wird abgeschlossen"
      unavailable={open.length > 0}
      describedBy={open.length > 0 ? hintId : undefined}
      onSubmit={() => void handleSubmit()}
      onBack={onBack}
    >
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
                py: 1.25,
                borderBottom: 1,
                borderColor: 'divider',
              }}
            >
              <Typography sx={{ fontSize: 14 }}>
                {STEP_INFO[s.step].label}
                <Typography component="span" sx={{ fontSize: 12.5, color: 'text.secondary' }}>
                  {s.required ? ' · Pflicht' : ' · optional'}
                </Typography>
              </Typography>
              <StateDot tone={TONE[status]} label={STEP_STATUS_LABEL[status]} />
            </Box>
          )
        })}
      </Box>
    </StepFrame>
  )
}
