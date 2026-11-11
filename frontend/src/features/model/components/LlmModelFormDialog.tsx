import { useCallback, useRef, useState } from 'react'
import {
  secretPart,
  maxTokensError,
  parseTemperature,
  temperatureError,
} from '@/shared/ui/setup/setupDrafts'
import Alert from '@mui/material/Alert'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import type { DiscoveredLlmModel, LlmModelResponse } from '@/shared/api/types'
import { discoverLlmModels, testLlmModel } from '../api/modelApi'
import { useLlmModelStore } from '../stores/llmModelStore'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import {
  displayNameFor,
  DEFAULT_MAX_TOKENS,
  suggestedMaxTokens,
} from '@/shared/ui/modelDiscovery/modelDiscovery'
import type { EndpointDiscoveryRequest } from '@/shared/ui/modelDiscovery/modelEndpoint'
import ModelEndpointFields from '@/shared/ui/setup/ModelEndpointFields'
import { ChatModelFields } from '@/shared/ui/setup/ModelParameterFields'
import SetupField from '@/shared/ui/setup/SetupField'
import { isBlank, urlError, type SetupErrors } from '@/shared/ui/setup/setupNames'
import { fontSize } from '@/shared/theme/tokens'
import FormDialog from '@/shared/ui/dialog/FormDialog'

interface LlmModelFormDialogProps {
  open: boolean
  /** The model being edited; `null` creates a new one. */
  model: LlmModelResponse | null
  onClose: () => void
  onSaved: (model: LlmModelResponse | null) => void
}

const NEW_DRAFT = {
  displayName: '',
  baseUrl: '',
  modelIdentifier: '',
  temperature: '',
  maxTokens: String(DEFAULT_MAX_TOKENS),
}

function draftFrom(model: LlmModelResponse | null) {
  if (!model) return NEW_DRAFT
  return {
    displayName: model.displayName,
    baseUrl: model.baseUrl,
    modelIdentifier: model.modelIdentifier,
    temperature: model.temperature == null ? '' : String(model.temperature),
    maxTokens: String(model.maxTokens),
  }
}

const DISPLAY_NAME_ID = 'llm-model-form-display-name'

/**
 * The form of a chat model — the same for creating and editing, with the endpoint and model
 * fields of the assistant's step „Chat-Modell“ (`ModelEndpointFields`, `ChatModelFields`) and,
 * here only, the display name of the list.
 *
 * A choice from the endpoint's list prefills display name and answer length as long as they were
 * not set by hand, and tests the connection at once. „Speichern“ is always active: a missing or
 * unreadable entry stands at its field, and the focus goes there.
 *
 * The caller gives the dialog a `key`, so that a change of model resets the draft —
 * MUI only unmounts the children on close, not the component.
 */
export default function LlmModelFormDialog({
  open,
  model,
  onClose,
  onSaved,
}: LlmModelFormDialogProps) {
  const createNewModel = useLlmModelStore((s) => s.createNewModel)
  const updateExistingModel = useLlmModelStore((s) => s.updateExistingModel)

  const [draft, setDraft] = useState(() => draftFrom(model))
  const [apiKey, setApiKey] = useState('')
  // An explicit request instead of „empty field means delete": the key is never read back, so
  // untouched and „please remove" could not be told apart otherwise.
  const [removeKey, setRemoveKey] = useState(false)
  const [errors, setErrors] = useState<SetupErrors>({})
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [testResult, setTestResult] = useState<{ success: boolean; message: string } | null>(null)
  const [testing, setTesting] = useState(false)
  // Prefilling overwrites only what the person has not set themselves; when editing, the
  // stored response length counts as set.
  const [suggestedName, setSuggestedName] = useState<string | null>(null)
  const [maxTokensTouched, setMaxTokensTouched] = useState(model !== null)
  const [contextLength, setContextLength] = useState<number | null>(null)
  // Only the result of the latest connection test counts, as in useModelDiscovery.
  const latestTest = useRef(0)
  const formRef = useRef<HTMLDivElement>(null)

  const isEdit = model !== null
  const titel = isEdit ? `„${model.displayName}“ bearbeiten` : 'Neues Modell'

  /**
   * What to send for `apiKey` — the three-way convention of `LlmModelRequest`: omitted means
   * unchanged, empty text means remove, anything else means set. Connection test and model
   * discovery get the same value: they never save anything, and an omitted key lets them use this
   * model's **stored** key — only for its own address.
   */
  const keyForRequest = secretPart(apiKey.trim(), removeKey)
  /** The stored key of the edited model serves only while its removal is not requested. */
  const storedKeyModelId = model && !removeKey ? model.id : undefined
  const storedModelId = model?.id

  const discover = useCallback(
    (request: EndpointDiscoveryRequest) =>
      discoverLlmModels({ ...request, modelId: storedKeyModelId, storedModelId }),
    [storedKeyModelId, storedModelId],
  )

  function close() {
    if (submitting) return
    onClose()
  }

  /** A new address or key invalidates the last test result, a running test included. */
  function connectionChanged() {
    latestTest.current++
    setTesting(false)
    setTestResult(null)
  }

  function fieldErrors(): SetupErrors {
    return {
      baseUrl: urlError(draft.baseUrl, 'die Basis-Adresse'),
      model: isBlank(draft.modelIdentifier) ? 'Modell-Kennung fehlt' : undefined,
      displayName: isBlank(draft.displayName) ? 'Anzeigename fehlt' : undefined,
      temperature: temperatureError(draft.temperature),
      maxTokens: maxTokensError(draft.maxTokens),
    }
  }

  /** Puts the errors at their fields and the focus into the first one; `true` when there are none. */
  function accept(found: SetupErrors): boolean {
    setErrors(found)
    if (!Object.values(found).some(Boolean)) return true
    requestAnimationFrame(() =>
      formRef.current?.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus(),
    )
    return false
  }

  function selectModel(selected: DiscoveredLlmModel) {
    const nameIsOurs = draft.displayName.trim() === '' || draft.displayName === suggestedName
    const name = displayNameFor(selected.identifier)
    setDraft((current) => ({
      ...current,
      modelIdentifier: selected.identifier,
      displayName: nameIsOurs ? name : current.displayName,
      maxTokens:
        !maxTokensTouched && selected.contextLength
          ? String(suggestedMaxTokens(selected.contextLength))
          : current.maxTokens,
    }))
    if (nameIsOurs) setSuggestedName(name)
    setContextLength(selected.contextLength ?? null)
    void handleTest(selected.identifier)
  }

  async function handleTest(modelIdentifier = draft.modelIdentifier) {
    const found = fieldErrors()
    const relevant = {
      baseUrl: found.baseUrl,
      model: isBlank(modelIdentifier) ? found.model : undefined,
      temperature: found.temperature,
    }
    if (!accept(relevant)) return
    const ticket = ++latestTest.current
    setTestResult(null)
    setTesting(true)
    let result: { success: boolean; message: string }
    try {
      result = await testLlmModel({
        baseUrl: draft.baseUrl.trim(),
        modelIdentifier: modelIdentifier.trim(),
        apiKey: keyForRequest,
        modelId: storedKeyModelId,
        storedModelId,
        temperature: parseTemperature(draft.temperature),
      })
    } catch (err) {
      result = {
        success: false,
        message: err instanceof Error ? err.message : 'Verbindungstest fehlgeschlagen',
      }
    }
    if (ticket !== latestTest.current) return
    setTestResult(result)
    setTesting(false)
  }

  async function handleSubmit() {
    if (!accept(fieldErrors())) return
    setError(null)
    setSubmitting(true)
    const request = {
      displayName: draft.displayName.trim(),
      baseUrl: draft.baseUrl.trim(),
      modelIdentifier: draft.modelIdentifier.trim(),
      temperature: parseTemperature(draft.temperature),
      maxTokens: Number(draft.maxTokens),
      apiKey: keyForRequest,
    }
    try {
      if (model) {
        const updated = await updateExistingModel(model.id, request)
        onSaved(updated)
      } else {
        await createNewModel(request)
        onSaved(null)
      }
      onClose()
    } catch (err) {
      setError(
        err instanceof Error
          ? err.message
          : isEdit
            ? 'Aktualisierung fehlgeschlagen'
            : 'Modell konnte nicht angelegt werden',
      )
    } finally {
      setSubmitting(false)
    }
  }

  // The statement was made for the stored combination; the backend resets it on save.
  const resetsToolCapability =
    model !== null &&
    model.toolCapability.value !== 'UNVERIFIED' &&
    (draft.baseUrl.trim() !== model.baseUrl ||
      draft.modelIdentifier.trim() !== model.modelIdentifier)

  return (
    <FormDialog
      open={open}
      title={titel}
      submitLabel={isEdit ? 'Speichern' : 'Anlegen'}
      onSubmit={() => void handleSubmit()}
      onClose={close}
      busy={submitting}
      busyAnnouncement="Das Modell wird gespeichert"
      secondaryAction={
        <BusyButton
          variant="outlined"
          onClick={() => void handleTest()}
          busy={testing}
          busyAnnouncement="Verbindung wird getestet"
        >
          Verbindung testen
        </BusyButton>
      }
    >
      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}
      {resetsToolCapability && (
        <Alert severity="info" sx={{ mb: 2 }}>
          Basis-Adresse oder Modell-Kennung geändert: Die Werkzeugfähigkeit ist nach dem Speichern
          wieder ungeprüft.
        </Alert>
      )}
      <Stack spacing={3} sx={{ mt: 1 }} ref={formRef}>
        <ModelEndpointFields
          purpose="chat"
          stored={model ? { baseUrl: model.baseUrl, apiKeySet: model.apiKeySet } : null}
          baseUrl={draft.baseUrl}
          onBaseUrlChange={(baseUrl) => {
            setDraft((current) => ({ ...current, baseUrl }))
            connectionChanged()
          }}
          apiKey={apiKey}
          onApiKeyChange={(next) => {
            setApiKey(next)
            connectionChanged()
          }}
          removeKey={removeKey}
          onRemoveKeyChange={(next) => {
            setRemoveKey(next)
            connectionChanged()
          }}
          model={draft.modelIdentifier}
          onModelChange={(modelIdentifier) => {
            setDraft((current) => ({ ...current, modelIdentifier }))
            setContextLength(null)
          }}
          onModelSelect={selectModel}
          errors={errors}
          discover={discover}
        >
          <SetupField
            id={DISPLAY_NAME_ID}
            label="Anzeigename"
            value={draft.displayName}
            onChange={(displayName) => setDraft((current) => ({ ...current, displayName }))}
            error={errors.displayName}
          />
          <ChatModelFields
            idPrefix="llm-model-form"
            temperature={draft.temperature}
            onTemperatureChange={(temperature) =>
              setDraft((current) => ({ ...current, temperature }))
            }
            maxTokens={draft.maxTokens}
            onMaxTokensChange={(maxTokens) => {
              setMaxTokensTouched(true)
              setDraft((current) => ({ ...current, maxTokens }))
            }}
            contextLength={contextLength}
            errors={errors}
          />
        </ModelEndpointFields>
        {testing && (
          <Typography role="status" sx={{ fontSize: fontSize.meta, color: 'text.secondary' }}>
            Verbindung wird getestet …
          </Typography>
        )}
        {testResult && (
          <Alert severity={testResult.success ? 'success' : 'error'}>{testResult.message}</Alert>
        )}
      </Stack>
    </FormDialog>
  )
}
