import { useId, useState } from 'react'
import { OPAA_BRANDING } from '@/shared/branding/brandingStore'
import type { InstallationStep, StepInput, StoredStepValues } from '../../api/types'
import type { WizardResult } from '../../hooks/useInstallationWizard'
import { useStepForm } from '../../hooks/useStepForm'
import { STEP_INFO } from '../../utils/steps'
import { compact, hexColorError, isBlank } from '../../utils/validation'
import FormField from '../FormField'
import StepFrame, { type StepNavigationProps } from '../StepFrame'

interface BrandingStepProps extends StepNavigationProps {
  step: InstallationStep
  submit: (values: StepInput['BRANDING']) => Promise<WizardResult>
}

/** Product name and accent; logo, claim and background follow under „Allgemein & Branding". */
export default function BrandingStep({ step, submit, onBack, onSkip }: BrandingStepProps) {
  const id = useId()
  const stored = step.values as StoredStepValues['BRANDING'] | null
  const [productName, setProductName] = useState(stored?.productName ?? OPAA_BRANDING.productName)
  const [primaryColor, setPrimaryColor] = useState(
    stored?.primaryColor ?? OPAA_BRANDING.primaryColor,
  )
  const form = useStepForm()

  function handleSubmit() {
    const localErrors = compact({
      productName: isBlank(productName) ? 'Bitte einen Produktnamen angeben.' : undefined,
      primaryColor: hexColorError(primaryColor),
    })
    void form.submit(localErrors, () =>
      submit({ productName: productName.trim(), primaryColor: primaryColor.trim() }),
    )
  }

  return (
    <StepFrame
      title={STEP_INFO.BRANDING.label}
      required={step.required}
      laterUnder={STEP_INFO.BRANDING.laterUnder}
      description="Der Name, unter dem Personen die Installation kennen, und ihre Akzentfarbe. Logo, Claim und Anmeldehintergrund stellen Sie nach dem Abschluss ein."
      failure={form.failure}
      busy={form.busy}
      submitLabel="Speichern und weiter"
      busyAnnouncement="Angaben werden gespeichert"
      onSubmit={handleSubmit}
      onBack={onBack}
      onSkip={onSkip}
    >
      <FormField
        id={`${id}-product-name`}
        label="Produktname"
        value={productName}
        onChange={setProductName}
        error={form.errors.productName}
      />
      <FormField
        id={`${id}-primary-color`}
        label="Akzentfarbe"
        value={primaryColor}
        onChange={setPrimaryColor}
        error={form.errors.primaryColor}
        hint="Als Hex-Wert. Ob die Farbe den Kontrast überall erreicht, zeigt später „Allgemein & Branding“."
      />
    </StepFrame>
  )
}
