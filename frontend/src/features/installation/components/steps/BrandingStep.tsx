import { useId, useState } from 'react'
import { previewColorOf, type BrandingDraft } from '@/shared/ui/setup/setupDrafts'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { OPAA_BRANDING } from '@/shared/branding/brandingStore'
import SectionHead from '@/shared/ui/SectionHead'
import BrandingPreviews from '@/shared/ui/branding/BrandingPreviews'
import {
  BRANDING_LABELS,
  BRANDING_TEXTS,
  isInvalidPrimaryColor,
} from '@/shared/ui/branding/brandingSettings'
import BrandingFields from '@/shared/ui/setup/BrandingFields'
import { fontSize } from '@/shared/theme/tokens'
import type { InstallationStepState, StepInput } from '../../api/types'
import type { WizardResult } from '../../hooks/useInstallationWizard'
import { useStepForm } from '../../hooks/useStepForm'
import { STEP_INFO } from '../../utils/steps'
import { compact } from '../../utils/validation'
import StepFrame, { type StepNavigationProps } from '../StepFrame'

const BRANDING_FIELDS = ['productName', 'claim', 'aiNotice', 'primaryColor']

/** Blank is the OPAA standard, sent as `null`. */
function orDefault(value: string): string | null {
  return value.trim() === '' ? null : value.trim()
}

interface BrandingStepProps extends StepNavigationProps {
  step: InstallationStepState
  submit: (values: StepInput['BRANDING']) => Promise<WizardResult>
}

/**
 * The optional branding, with the text and colour fields of „Administration → Allgemein & Branding“ and its
 * preview. Every field left as it is keeps the OPAA standard, so a step saved without changes
 * writes nothing; the images stay with the administration, as the step's sentence says.
 */
export default function BrandingStep({ step, submit, onBack, onSkip }: BrandingStepProps) {
  const id = useId()
  const stored = step.branding ?? null
  const aiNoticeDefault = OPAA_BRANDING.aiNoticeDefault
  const [draft, setDraft] = useState<BrandingDraft>({
    productName: stored?.productName ?? '',
    claim: stored?.claim ?? '',
    aiNotice: stored?.aiNotice ?? aiNoticeDefault,
    primaryColor: stored?.primaryColor ?? '',
  })
  const form = useStepForm(step.checked ? null : step.checkMessage)

  function handleSubmit() {
    const localErrors = compact({
      primaryColor: isInvalidPrimaryColor(draft.primaryColor)
        ? BRANDING_TEXTS.primaryColorInvalid
        : undefined,
    })
    void form.submit(localErrors, BRANDING_FIELDS, () =>
      submit({
        productName: orDefault(draft.productName),
        claim: orDefault(draft.claim),
        // The standard text is sent as "unset", so it keeps following the standard.
        aiNotice: draft.aiNotice.trim() === aiNoticeDefault ? null : orDefault(draft.aiNotice),
        primaryColor: orDefault(draft.primaryColor),
      }),
    )
  }

  return (
    <StepFrame
      title={STEP_INFO.BRANDING.label}
      mandatory={step.mandatory}
      laterUnder={STEP_INFO.BRANDING.laterUnder}
      description="Name, Claim, Hinweis zur KI-Nutzung und Akzentfarbe Ihres Hauses. Übersprungen gilt der OPAA-Standard; Logo und Hintergrundbild lädt die Systemverwaltung später unter „Administration → Allgemein & Branding“ hoch."
      failure={form.failure}
      busy={form.busy}
      busyAnnouncement="Branding wird gespeichert und geprüft"
      onSubmit={handleSubmit}
      onBack={onBack}
      onSkip={onSkip}
    >
      <BrandingFields
        idPrefix={id}
        value={draft}
        onChange={(patch) => setDraft((current) => ({ ...current, ...patch }))}
        aiNoticeDefault={aiNoticeDefault}
        errors={form.errors}
      />
      <Box component="section" aria-labelledby={`${id}-preview`}>
        <SectionHead component="h3" id={`${id}-preview`}>
          {BRANDING_LABELS.preview}
        </SectionHead>
        <Typography sx={{ fontSize: fontSize.text, color: 'text.secondary', mb: 1.5 }}>
          {BRANDING_TEXTS.previewHint}
        </Typography>
        <BrandingPreviews
          productName={draft.productName.trim() || OPAA_BRANDING.productName}
          claim={draft.claim.trim() || OPAA_BRANDING.claim}
          primaryColor={previewColorOf(draft.primaryColor)}
        />
      </Box>
    </StepFrame>
  )
}
