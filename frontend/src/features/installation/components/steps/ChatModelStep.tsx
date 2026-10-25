import { useId, useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'
import type { InstallationStepState, StepInput } from '../../api/types'
import type { WizardResult } from '../../hooks/useInstallationWizard'
import { useStepForm } from '../../hooks/useStepForm'
import { STEP_INFO } from '../../utils/steps'
import { compact, isBlank, urlError } from '../../utils/validation'
import FormField from '../FormField'
import SecretInput from '../SecretInput'
import StepFrame, { type StepNavigationProps } from '../StepFrame'

interface ChatModelStepProps extends StepNavigationProps {
  step: InstallationStepState
  submit: (values: StepInput['CHAT_MODEL']) => Promise<WizardResult>
}

const DEFAULT_MAX_TOKENS = '8000'
const MAX_TEMPERATURE = 2

function temperatureError(value: string): string | undefined {
  if (isBlank(value)) return undefined
  const number = Number(value.replace(',', '.'))
  return Number.isFinite(number) && number >= 0 && number <= MAX_TEMPERATURE
    ? undefined
    : `Die Temperatur ist eine Zahl von 0 bis ${MAX_TEMPERATURE}. Für keine Temperatur das Feld leeren.`
}

function maxTokensError(value: string): string | undefined {
  if (isBlank(value)) return undefined
  const number = Number(value)
  return Number.isInteger(number) && number > 0
    ? undefined
    : 'Die maximale Antwortlänge ist eine ganze Zahl größer als 0.'
}

/**
 * The model that answers in the chat, over an OpenAI-compatible interface. Its check is the
 * connection test of the model administration; a stored key is never shown again.
 */
export default function ChatModelStep({ step, submit, onBack, onSkip }: ChatModelStepProps) {
  const id = useId()
  const stored = step.chatModel ?? null
  const [baseUrl, setBaseUrl] = useState(stored?.baseUrl ?? '')
  const [model, setModel] = useState(stored?.model ?? '')
  const [apiKey, setApiKey] = useState('')
  const [removeKey, setRemoveKey] = useState(false)
  const keyStored = Boolean(stored?.apiKeySet)
  const [temperature, setTemperature] = useState(
    stored?.temperature == null ? '' : String(stored.temperature),
  )
  const [maxTokens, setMaxTokens] = useState(
    stored?.maxTokens == null ? DEFAULT_MAX_TOKENS : String(stored.maxTokens),
  )
  const form = useStepForm(step.checked ? null : step.checkMessage)

  function handleSubmit() {
    const localErrors = compact({
      baseUrl: urlError(baseUrl, 'die Basis-Adresse'),
      model: isBlank(model) ? 'Bitte die Modell-Kennung angeben.' : undefined,
      temperature: temperatureError(temperature),
      maxTokens: maxTokensError(maxTokens),
    })
    void form.submit(localErrors, () =>
      submit({
        baseUrl: baseUrl.trim(),
        model: model.trim(),
        // Omitted keeps the stored key, an empty one removes it (InstallationChatModel).
        ...(apiKey !== '' ? { apiKey } : removeKey ? { apiKey: '' } : {}),
        temperature: isBlank(temperature) ? null : Number(temperature.replace(',', '.')),
        maxTokens: isBlank(maxTokens) ? null : Number(maxTokens),
      }),
    )
  }

  return (
    <StepFrame
      title={STEP_INFO.CHAT_MODEL.label}
      mandatory={step.mandatory}
      laterUnder={STEP_INFO.CHAT_MODEL.laterUnder}
      description="Das Modell, das im Chat antwortet. OPAA spricht es über eine OpenAI-kompatible Schnittstelle an, etwa Ollama, vLLM oder einen Cloud-Anbieter. Die Prüfung ist ein Verbindungstest."
      failure={form.failure}
      busy={form.busy}
      busyAnnouncement="Verbindung zum Modell wird geprüft"
      onSubmit={handleSubmit}
      onBack={onBack}
      onSkip={onSkip}
    >
      <FormField
        id={`${id}-base-url`}
        label="Basis-Adresse"
        inputMode="url"
        value={baseUrl}
        onChange={setBaseUrl}
        error={form.errors.baseUrl}
        hint="Etwa http://ollama:11434/v1, so wie das Backend den Dienst erreicht."
      />
      <FormField
        id={`${id}-model`}
        label="Modell-Kennung"
        value={model}
        onChange={setModel}
        error={form.errors.model}
        hint="Der Name, unter dem der Dienst das Modell führt, etwa qwen2.5:7b."
      />
      <SecretInput
        id={`${id}-api-key`}
        label="API-Schlüssel"
        optional
        autoComplete="off"
        value={apiKey}
        onChange={setApiKey}
        stored={keyStored && !removeKey}
        error={form.errors.apiKey}
        hint="Leer lassen, wenn der Dienst keinen Schlüssel verlangt."
      />
      {keyStored && (
        <Box sx={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 1, mt: -1 }}>
          {removeKey && (
            <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
              Der gespeicherte Schlüssel wird beim Speichern entfernt.
            </Typography>
          )}
          <Button
            size="small"
            variant="text"
            onClick={() => {
              setRemoveKey((remove) => !remove)
              setApiKey('')
            }}
          >
            {removeKey ? 'Schlüssel behalten' : 'Gespeicherten Schlüssel entfernen'}
          </Button>
        </Box>
      )}
      <FormField
        id={`${id}-temperature`}
        label="Temperatur"
        optional
        inputMode="decimal"
        value={temperature}
        onChange={setTemperature}
        error={form.errors.temperature}
        hint="Leer lassen, um die Vorgabe des Modells zu nutzen."
      />
      <FormField
        id={`${id}-max-tokens`}
        label="Maximale Antwortlänge (Token)"
        optional
        inputMode="numeric"
        value={maxTokens}
        onChange={setMaxTokens}
        error={form.errors.maxTokens}
        hint={`Leer lassen für ${DEFAULT_MAX_TOKENS} Token.`}
      />
    </StepFrame>
  )
}
