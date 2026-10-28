import { useId, useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import FormControlLabel from '@mui/material/FormControlLabel'
import InputAdornment from '@mui/material/InputAdornment'
import Radio from '@mui/material/Radio'
import RadioGroup from '@mui/material/RadioGroup'
import Typography from '@mui/material/Typography'
import type { ColorScheme } from '@/shared/api/types'
import { OPAA_BRANDING } from '@/shared/branding/brandingStore'
import AccentContrastWarning from '@/shared/ui/branding/AccentContrastWarning'
import BrandingPreviews from '@/shared/ui/branding/BrandingPreviews'
import {
  BRANDING_COLOR_SCHEME_OPTIONS,
  BRANDING_LABELS,
  BRANDING_LIMITS,
  BRANDING_TEXTS,
  isInvalidPrimaryColor,
  singleLineNotice,
} from '@/shared/ui/branding/brandingSettings'
import type { InstallationStepState, StepInput } from '../../api/types'
import type { WizardResult } from '../../hooks/useInstallationWizard'
import { useStepForm } from '../../hooks/useStepForm'
import { STEP_INFO } from '../../utils/steps'
import { compact } from '../../utils/validation'
import FormField from '../FormField'
import StepFrame, { type StepNavigationProps } from '../StepFrame'

const BRANDING_FIELDS = ['productName', 'claim', 'aiNotice', 'primaryColor', 'defaultColorScheme']

/** Blank is the OPAA standard, sent as `null`. */
function orDefault(value: string): string | null {
  return value.trim() === '' ? null : value.trim()
}

interface BrandingStepProps extends StepNavigationProps {
  step: InstallationStepState
  submit: (values: StepInput['BRANDING']) => Promise<WizardResult>
}

/**
 * The optional branding: the texts, accent colour and colour scheme of „Administration →
 * Branding", with its labels, hints, rules, contrast warning and preview. Every field left as it
 * is keeps the OPAA standard, so a step saved without changes writes nothing; the images stay with
 * the administration.
 */
export default function BrandingStep({ step, submit, onBack, onSkip }: BrandingStepProps) {
  const id = useId()
  const stored = step.branding ?? null
  const aiNoticeDefault = OPAA_BRANDING.aiNoticeDefault
  const [productName, setProductName] = useState(stored?.productName ?? '')
  const [claim, setClaim] = useState(stored?.claim ?? '')
  const [aiNotice, setAiNotice] = useState(stored?.aiNotice ?? aiNoticeDefault)
  const [primaryColor, setPrimaryColor] = useState(stored?.primaryColor ?? '')
  const [colorScheme, setColorScheme] = useState<ColorScheme>(
    stored?.defaultColorScheme ?? OPAA_BRANDING.defaultColorScheme,
  )
  const form = useStepForm(step.checked ? null : step.checkMessage)
  const colorInvalid = isInvalidPrimaryColor(primaryColor)
  const colorSchemeLabelId = `${id}-color-scheme`
  const aiNoticeId = `${id}-ai-notice`
  const previewColor =
    primaryColor.trim() === '' || colorInvalid ? OPAA_BRANDING.primaryColor : primaryColor.trim()

  function handleSubmit() {
    const localErrors = compact({
      primaryColor: colorInvalid ? BRANDING_TEXTS.primaryColorInvalid : undefined,
    })
    void form.submit(localErrors, BRANDING_FIELDS, () =>
      submit({
        productName: orDefault(productName),
        claim: orDefault(claim),
        // The standard text is sent as "unset", so it keeps following the standard.
        aiNotice: aiNotice.trim() === aiNoticeDefault ? null : orDefault(aiNotice),
        primaryColor: orDefault(primaryColor),
        defaultColorScheme:
          colorScheme === OPAA_BRANDING.defaultColorScheme ? undefined : colorScheme,
      }),
    )
  }

  return (
    <StepFrame
      title={STEP_INFO.BRANDING.label}
      mandatory={step.mandatory}
      laterUnder={STEP_INFO.BRANDING.laterUnder}
      description={
        <>
          Name, Claim, Hinweis zur KI-Nutzung, Akzentfarbe und Farbschema-Vorgabe Ihres Hauses. Der
          Schritt ist optional: Was leer bleibt oder übersprungen wird, zeigt den OPAA-Standard, und
          alles lässt sich später unter „Administration → Branding“ ändern. Logo, Logo der
          Anmeldeseite und Hintergrundbild lädt die Systemverwaltung erst dort hoch; der Assistent
          nimmt keine Bilder an.
        </>
      }
      failure={form.failure}
      busy={form.busy}
      busyAnnouncement="Branding wird gespeichert und geprüft"
      onSubmit={handleSubmit}
      onBack={onBack}
      onSkip={onSkip}
    >
      <FormField
        id={`${id}-product-name`}
        label={BRANDING_LABELS.productName}
        optional
        autoComplete="off"
        value={productName}
        onChange={setProductName}
        maxLength={BRANDING_LIMITS.productName}
        error={form.errors.productName}
        hint={BRANDING_TEXTS.productNameHint}
      />
      <FormField
        id={`${id}-claim`}
        label={BRANDING_LABELS.claim}
        optional
        autoComplete="off"
        value={claim}
        onChange={setClaim}
        maxLength={BRANDING_LIMITS.claim}
        error={form.errors.claim}
        hint={BRANDING_TEXTS.claimHint}
      />
      <Box>
        <FormField
          id={aiNoticeId}
          label={BRANDING_LABELS.aiNotice}
          value={aiNotice}
          onChange={(value) => setAiNotice(singleLineNotice(value))}
          // Enter would insert a break the server rejects; pasted ones become spaces above.
          onKeyDown={(event) => {
            if (event.key === 'Enter') event.preventDefault()
          }}
          multiline
          minRows={2}
          maxLength={BRANDING_LIMITS.aiNotice}
          error={form.errors.aiNotice}
          hint={BRANDING_TEXTS.aiNoticeHint}
        />
        <Button
          size="small"
          sx={{ mt: 1 }}
          onClick={() => {
            // The focus moves to the field before the button turns disabled.
            document.getElementById(aiNoticeId)?.focus()
            setAiNotice(aiNoticeDefault)
          }}
          disabled={aiNotice === aiNoticeDefault}
        >
          {BRANDING_TEXTS.aiNoticeReset}
        </Button>
      </Box>
      <FormField
        id={`${id}-primary-color`}
        label={BRANDING_LABELS.primaryColor}
        optional
        autoComplete="off"
        value={primaryColor}
        onChange={setPrimaryColor}
        error={form.errors.primaryColor}
        hint={BRANDING_TEXTS.primaryColorHint}
        endAdornment={
          <InputAdornment position="end">
            <Box
              component="input"
              type="color"
              aria-label={`${BRANDING_LABELS.primaryColor} ${BRANDING_LABELS.colorPicker.toLowerCase()}`}
              value={previewColor.toLowerCase()}
              onChange={(event: React.ChangeEvent<HTMLInputElement>) =>
                setPrimaryColor(event.target.value.toUpperCase())
              }
              sx={{
                width: 32,
                height: 24,
                p: 0,
                border: 0,
                bgcolor: 'transparent',
                cursor: 'pointer',
              }}
            />
          </InputAdornment>
        }
      />
      <AccentContrastWarning primaryColor={primaryColor.trim()} />
      <Box>
        <Typography
          id={colorSchemeLabelId}
          sx={{ fontSize: 12, color: 'text.secondary', mb: '5px' }}
        >
          {BRANDING_LABELS.colorScheme}
        </Typography>
        <RadioGroup
          row
          value={colorScheme}
          onChange={(event) => setColorScheme(event.target.value as ColorScheme)}
          aria-labelledby={colorSchemeLabelId}
          aria-describedby={`${colorSchemeLabelId}-hint`}
        >
          {BRANDING_COLOR_SCHEME_OPTIONS.map((option) => (
            <FormControlLabel
              key={option.value}
              value={option.value}
              control={<Radio size="small" />}
              label={option.label}
            />
          ))}
        </RadioGroup>
        <Typography
          id={`${colorSchemeLabelId}-hint`}
          sx={{ fontSize: 12, color: 'text.secondary', mt: 0.5 }}
        >
          {BRANDING_TEXTS.colorSchemeHint}
        </Typography>
      </Box>
      <Box component="section" aria-labelledby={`${id}-preview`}>
        <Typography id={`${id}-preview`} component="h3" sx={{ fontSize: 14, fontWeight: 600 }}>
          {BRANDING_LABELS.preview}
        </Typography>
        <Typography sx={{ fontSize: 13, color: 'text.secondary', mt: 0.5, mb: 1.5 }}>
          {BRANDING_TEXTS.previewHint}
        </Typography>
        <BrandingPreviews
          productName={productName.trim() || OPAA_BRANDING.productName}
          claim={claim.trim() || OPAA_BRANDING.claim}
          primaryColor={previewColor}
        />
      </Box>
    </StepFrame>
  )
}
