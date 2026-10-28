import { useId, useState } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import StateDot from '@/shared/ui/StateDot'
import type { InstallationStatusResponse } from '../api/types'
import type { WizardResult } from '../hooks/useInstallationWizard'
import {
  STEP_INFO,
  STEP_STATUS_LABEL,
  STEP_STATUS_TONE,
  SUMMARY_LABEL,
  findStep,
  openSteps,
  stepStatus,
} from '../utils/steps'
import StepFrame, { type StepNavigationProps } from './StepFrame'

interface SummaryStepProps extends Pick<StepNavigationProps, 'onBack'> {
  installation: InstallationStatusResponse
  complete: () => Promise<WizardResult>
}

/**
 * Every step with its state, and the completion. Whether it can complete is the API's verdict
 * (`completable`). The action stays visible but refuses until then, and the sentence above it
 * names the open steps - a hidden or merely greyed-out button would leave the reason to guesswork.
 * A checked mail step also shows the backend's sentence about its check, which says whether a test
 * message went out.
 */
export default function SummaryStep({ installation, complete, onBack }: SummaryStepProps) {
  const hintId = useId()
  const [failure, setFailure] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const open = openSteps(installation)
  const skippedModels = installation.steps.filter(
    (s) => !s.mandatory && !s.saved && (s.step === 'CHAT_MODEL' || s.step === 'EMBEDDING_MODEL'),
  )
  const modelSentence =
    skippedModels.length === 0
      ? ''
      : ` Übersprungen: ${skippedModels.map((s) => STEP_INFO[s.step].label).join(', ')}. Ohne Chat-Modell beantwortet der Chat keine Fragen, ohne Embedding-Modell gibt es keinen Suchindex; beides legt die Systemverwaltung später unter Modelle fest.`
  const blocked = !installation.completable
  const mail = findStep(installation, 'MAIL')
  const mailSentence = !mail
    ? ''
    : mail.checked
      ? ' Der E-Mail-Versand wird mit dem geprüften Mailserver eingeschaltet.'
      : ' Der E-Mail-Versand bleibt aus; er lässt sich später unter „Administration → E-Mail“ einrichten.'

  async function handleSubmit() {
    if (blocked) return
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
        blocked ? (
          <span id={hintId}>
            Abschließen lässt sich die Installation erst, wenn alle Pflichtschritte geprüft sind und
            jeder ausgefüllte optionale Schritt geprüft oder übersprungen ist.
            {open.length > 0 && ` Offen: ${open.map((s) => STEP_INFO[s.step].label).join(', ')}.`}
          </span>
        ) : (
          `Alle Pflichtschritte sind geprüft. Der Abschluss legt Konto und Anmeldeverfahren an, dazu, was die geprüften optionalen Schritte festlegen, und beendet die Einrichtung; danach melden Sie sich mit dem ersten Konto an. Das Token verliert seine Gültigkeit.${modelSentence}${mailSentence}`
        )
      }
      failure={failure}
      busy={busy}
      submitLabel="Installation abschließen"
      busyAnnouncement="Installation wird abgeschlossen"
      unavailable={blocked}
      describedBy={blocked ? hintId : undefined}
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
                py: 1.5,
                borderBottom: 1,
                borderColor: 'divider',
                // The action bar's divider closes the list; a second rule would double it.
                '&:last-of-type': { borderBottom: 0 },
              }}
            >
              <Box>
                <Typography sx={{ fontSize: 14 }}>
                  {STEP_INFO[s.step].label}
                  <Typography component="span" sx={{ fontSize: 12.5, color: 'text.secondary' }}>
                    {s.mandatory ? ' · Pflicht' : ' · optional'}
                  </Typography>
                </Typography>
                {s.step === 'MAIL' && s.checked && s.checkMessage && (
                  <Typography sx={{ fontSize: 13, color: 'text.secondary', mt: 0.5 }}>
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
