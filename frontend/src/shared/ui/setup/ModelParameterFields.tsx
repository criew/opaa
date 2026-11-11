import { formatTokens } from '../modelDiscovery/modelDiscovery'
import SetupField from './SetupField'
import { DEFAULT_MAX_INPUT_TOKENS } from './setupDrafts'
import type { SetupErrors } from './setupNames'

interface ChatModelFieldsProps {
  idPrefix: string
  temperature: string
  onTemperatureChange: (value: string) => void
  maxTokens: string
  onMaxTokensChange: (value: string) => void
  /** The context window the endpoint reported for the chosen model, if any. */
  contextLength: number | null
  /** Errors by `temperature` and `maxTokens`. */
  errors: SetupErrors
}

/** Temperature and answer length of a chat model, below its identifier. */
export function ChatModelFields({
  idPrefix,
  temperature,
  onTemperatureChange,
  maxTokens,
  onMaxTokensChange,
  contextLength,
  errors,
}: ChatModelFieldsProps) {
  return (
    <>
      <SetupField
        id={`${idPrefix}-temperature`}
        label="Temperatur"
        optional
        numeric
        inputMode="decimal"
        value={temperature}
        onChange={onTemperatureChange}
        error={errors.temperature}
      />
      <SetupField
        id={`${idPrefix}-max-tokens`}
        label="Maximale Antwortlänge (Token)"
        numeric
        inputMode="numeric"
        value={maxTokens}
        onChange={onMaxTokensChange}
        error={errors.maxTokens}
        hint={
          contextLength
            ? `Kontextfenster des Modells: ${formatTokens(contextLength)} Token. Vorgeschlagen ist höchstens die Hälfte davon.`
            : undefined
        }
      />
    </>
  )
}

interface EmbeddingModelFieldsProps {
  idPrefix: string
  maxInputTokens: string
  onMaxInputTokensChange: (value: string) => void
  contextLength: number | null
  /** Errors by `maxInputTokens`. */
  errors: SetupErrors
}

/** The input limit of an embedding model, below its identifier. */
export function EmbeddingModelFields({
  idPrefix,
  maxInputTokens,
  onMaxInputTokensChange,
  contextLength,
  errors,
}: EmbeddingModelFieldsProps) {
  return (
    <SetupField
      id={`${idPrefix}-max-input-tokens`}
      label="Eingabegrenze (Token)"
      optional
      numeric
      inputMode="numeric"
      value={maxInputTokens}
      onChange={onMaxInputTokensChange}
      error={errors.maxInputTokens}
      hint={
        contextLength
          ? `Der Endpunkt meldet ein Kontextfenster von ${formatTokens(contextLength)} Token; mehr nimmt das Modell nicht an.`
          : `So viele Token nimmt der Dienst je Abschnitt an. Leer lassen für ${DEFAULT_MAX_INPUT_TOKENS}.`
      }
    />
  )
}
