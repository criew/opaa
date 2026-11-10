import { useEffect, useRef, type ReactNode } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'
import { fontSize } from '../../theme/tokens'
import PageHeading from '../a11y/PageHeading'
import BusyButton from '../a11y/BusyButton'
import WizardStepBar from './WizardStepBar'
import type { WizardSteps } from './wizardSteps'

/** The content width of every assistant (guidelines 5.15); fields span all of it. */
export const WIZARD_WIDTH = 720

interface WizardLayoutProps {
  /** The object being created, as the line above the title says it („Neuer Space“). */
  object: string
  steps: WizardSteps
  activeStep: number
  /** A field of the step refused its answer: the document title starts with „Fehler: “. */
  failed?: boolean
  /** Why the person may not create the object; „Anlegen“ then leads to this hint. */
  createBlockedBy?: string | null
  /** A failure of the step or of the creation that belongs to no field. */
  error?: string | null
  /** Hints of the step between the step bar and its fields. */
  notice?: ReactNode
  /** Said beside the buttons, such as „2 Freigaben vorgemerkt“. */
  footerNote?: ReactNode
  /** A line above the buttons; off where the step's content ends in a line of its own. */
  footerSeparated?: boolean
  /** Moves the focus to the step title when the page mounts, e.g. on the return from a provider. */
  focusTitleOnMount?: boolean
  submitting: boolean
  /** Announced while the creation runs („Space wird angelegt“). */
  createAnnouncement: string
  onCancel: () => void
  onBack: () => void
  onNext: () => void
  onCreate: () => void
  children: ReactNode
}

/**
 * The frame of every creation assistant (guidelines 5.15): the object above the step name as
 * title, the step bar, the step's fields at {@link WIZARD_WIDTH} and the footer „Abbrechen“ ·
 * „Zurück“ · „Weiter“, in the last step „Anlegen“. Neither „Weiter“ nor „Anlegen“ is ever
 * disabled: a missing answer is reported at its field, a missing right by the hint „Anlegen“
 * leads to, and a running creation swallows a second click. After a step change the focus moves
 * to the new step's title.
 */
export default function WizardLayout({
  object,
  steps,
  activeStep,
  failed = false,
  createBlockedBy = null,
  error = null,
  notice,
  footerNote,
  footerSeparated = true,
  focusTitleOnMount = false,
  submitting,
  createAnnouncement,
  onCancel,
  onBack,
  onNext,
  onCreate,
  children,
}: WizardLayoutProps) {
  const step = steps[Math.min(activeStep, steps.length - 1)]
  const lastStep = activeStep >= steps.length - 1
  const titleRef = useRef<HTMLHeadingElement>(null)
  const blockedRef = useRef<HTMLDivElement>(null)
  const firstRender = useRef(true)

  useEffect(() => {
    if (firstRender.current) {
      firstRender.current = false
      if (!focusTitleOnMount) return
    }
    titleRef.current?.focus()
  }, [activeStep, focusTitleOnMount])

  const documentTitle = `${failed || error ? 'Fehler: ' : ''}${step} – Schritt ${activeStep + 1} von ${steps.length} – ${object}`

  return (
    <Box sx={{ flexGrow: 1, overflowY: 'auto', p: { xs: 2.5, md: 5 } }}>
      <Box sx={{ maxWidth: WIZARD_WIDTH }}>
        <PageHeading
          title={object}
          documentTitle={documentTitle}
          variant="body2"
          sx={{ color: 'text.secondary', mb: 0.5 }}
        />
        <Typography
          ref={titleRef}
          component="h2"
          variant="h2"
          tabIndex={-1}
          sx={{ fontSize: fontSize.wizardTitle, mb: 3 }}
        >
          {step}
        </Typography>
        <WizardStepBar steps={steps} active={activeStep} />

        {createBlockedBy && (
          <Alert
            ref={blockedRef}
            severity="info"
            tabIndex={-1}
            id="wizard-create-blocked"
            sx={{ mb: 2 }}
          >
            {createBlockedBy}
          </Alert>
        )}
        {/* A failure is announced without moving the focus (WCAG 4.1.3). */}
        <Box role="alert" aria-live="polite">
          {error && (
            <Alert severity="error" sx={{ mb: 2 }}>
              {error}
            </Alert>
          )}
        </Box>
        {notice}

        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2.5 }}>{children}</Box>

        <Box
          sx={{
            display: 'flex',
            flexWrap: 'wrap',
            alignItems: 'center',
            gap: { xs: 1, sm: 1.5 },
            mt: footerSeparated ? 4 : 2,
            pt: footerSeparated ? 2 : 0,
            borderTop: footerSeparated ? 1 : 0,
            borderColor: 'divider',
          }}
        >
          <Button variant="text" onClick={onCancel} disabled={submitting} sx={{ mr: 'auto' }}>
            Abbrechen
          </Button>
          {footerNote && (
            // below 600 px the note takes a line of its own above the buttons
            <Typography
              variant="body2"
              sx={{
                color: 'text.secondary',
                order: { xs: -1, sm: 0 },
                flexBasis: { xs: '100%', sm: 'auto' },
                textAlign: 'right',
              }}
            >
              {footerNote}
            </Typography>
          )}
          {activeStep > 0 && (
            <Button variant="outlined" onClick={onBack} disabled={submitting}>
              Zurück
            </Button>
          )}
          {lastStep ? (
            <BusyButton
              variant="contained"
              busy={submitting}
              busyAnnouncement={createAnnouncement}
              aria-describedby={createBlockedBy ? 'wizard-create-blocked' : undefined}
              onClick={() => {
                if (createBlockedBy) blockedRef.current?.focus()
                else onCreate()
              }}
            >
              Anlegen
            </BusyButton>
          ) : (
            <Button variant="contained" onClick={onNext}>
              Weiter
            </Button>
          )}
        </Box>
      </Box>
    </Box>
  )
}
