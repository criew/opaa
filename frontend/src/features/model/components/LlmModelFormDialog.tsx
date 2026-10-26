import { useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import type { DiscoveredLlmModel, LlmModelResponse } from '@/shared/api/types'
import { testLlmModel } from '../api/modelApi'
import { useLlmModelStore } from '../stores/llmModelStore'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import SectionHead from '@/shared/ui/SectionHead'
import BaseUrlField from './BaseUrlField'
import DiscoveryStatus from './DiscoveryStatus'
import ModelIdentifierField from './ModelIdentifierField'
import {
  displayNameFor,
  formatTokens,
  DEFAULT_MAX_TOKENS,
  presetOf,
  suggestedMaxTokens,
  type EndpointPreset,
} from './modelDiscovery'
import { useModelDiscovery } from './useModelDiscovery'

export const REQUIRED_FIELDS_HINT =
  'Anzeigename, Basis-Adresse, Modell-Kennung und maximale Antwortlänge sind erforderlich.'

export const TEMPERATURE_HELP_TEXT =
  'Leer lassen, um die Voreinstellung des Modells zu verwenden. Manche Modelle lehnen jede ' +
  'Temperatur ab.'

export const TEMPERATURE_UNREADABLE_HINT =
  'Die Temperatur ist keine gültige Zahl. Für keine Temperatur das Feld leeren.'

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

/**
 * The form of a chat model — the same for creating and editing, like
 * `UserFormDialog` under „Benutzer".
 *
 * Guided in three sections: endpoint (template or own address, key,
 * „Modelle abrufen"), model (choice from the endpoint's list or free input) and display.
 * A choice prefills display name and response length as long as they were not set by hand,
 * and tests the connection at once.
 *
 * No provider select field: the interface speaks only the OpenAI-compatible
 * protocol; the templates are just addresses.
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
  const [apiKeyInput, setApiKeyInput] = useState('')
  const [apiKeyTouched, setApiKeyTouched] = useState(false)
  // An explicit request instead of „empty field means delete" - the field
  // starts empty for every model because the key is never read back; untouched-empty and
  // „please remove" could not be told apart otherwise.
  const [apiKeyClearRequested, setApiKeyClearRequested] = useState(false)
  const [temperatureUnreadable, setTemperatureUnreadable] = useState(false)
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
  const discovery = useModelDiscovery()
  const addressRef = useRef<HTMLInputElement>(null)

  const isEdit = model !== null
  const titel = isEdit ? `„${model.displayName}“ bearbeiten` : 'Chat-Modell anlegen'
  const requiredHintId = 'llm-model-form-required-hint'
  const preset = presetOf(draft.baseUrl)
  const typedKey = apiKeyTouched && apiKeyInput.trim() !== ''
  const temperatureFieldId = 'llm-model-form-temperature'
  // MUI gives the helper line of a TextField with `id` the ID `<id>-helper-text`.
  const temperatureHelperId = `${temperatureFieldId}-helper-text`

  function isValid() {
    return (
      draft.displayName.trim() !== '' &&
      draft.baseUrl.trim() !== '' &&
      draft.modelIdentifier.trim() !== '' &&
      draft.maxTokens.trim() !== ''
    )
  }

  /**
   * Resolves what to send for `apiKey` — the three-way convention of `LlmModelRequest`:
   * omitted means unchanged, empty text means remove, anything else means set. An emptied field
   * counts as untouched; only „Gespeicherten Schlüssel entfernen" sends the empty text.
   *
   * Connection test and model discovery get the same value, although the empty text means
   * something else there: they never save anything, and an omitted key lets them keep using
   * this model's **stored** key — only for its own address.
   */
  function resolveApiKey(): string | undefined {
    if (apiKeyClearRequested) return ''
    if (apiKeyTouched && apiKeyInput.trim() !== '') return apiKeyInput.trim()
    return undefined
  }

  /** An empty field means „keine Angabe“: the parameter is then not sent to the model at all. */
  function resolveTemperature(): number | null {
    return draft.temperature.trim() === '' ? null : Number(draft.temperature)
  }

  function close() {
    if (submitting) return
    onClose()
  }

  /**
   * A new address invalidates the list and the last test result, a running test included; both
   * belonged to the old one.
   */
  function changeBaseUrl(baseUrl: string) {
    setDraft((current) => ({ ...current, baseUrl }))
    discovery.reset()
    latestTest.current++
    setTesting(false)
    setTestResult(null)
  }

  /** The stored key of the edited model serves only while its removal is not requested. */
  function storedKeyModelId(): string | undefined {
    return model && !apiKeyClearRequested ? model.id : undefined
  }

  function runDiscovery(baseUrl = draft.baseUrl) {
    if (baseUrl.trim() === '') return
    void discovery.discover({
      baseUrl: baseUrl.trim(),
      apiKey: resolveApiKey(),
      modelId: storedKeyModelId(),
    })
  }

  /**
   * Fills in the preset's address. A host still to be confirmed (Ollama) is selected for
   * overwriting and nothing is fetched yet; a keyed preset fetches at once if a key is typed.
   */
  function choosePreset(chosen: EndpointPreset) {
    changeBaseUrl(chosen.baseUrl)
    const host = chosen.placeholderHost
    if (host !== null) {
      // After the combobox has written the chosen address into the input.
      setTimeout(() => {
        const input = addressRef.current
        if (!input) return
        const start = chosen.baseUrl.indexOf(host)
        input.focus()
        input.setSelectionRange(start, start + host.length)
      }, 0)
      return
    }
    if (chosen.needsKey && typedKey) runDiscovery(chosen.baseUrl)
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
    const ticket = ++latestTest.current
    setTestResult(null)
    setTesting(true)
    let result: { success: boolean; message: string }
    try {
      result = await testLlmModel({
        baseUrl: draft.baseUrl.trim(),
        modelIdentifier: modelIdentifier.trim(),
        apiKey: resolveApiKey(),
        modelId: storedKeyModelId(),
        temperature: resolveTemperature(),
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
    if (!isValid()) {
      setError(REQUIRED_FIELDS_HINT)
      return
    }
    if (temperatureUnreadable) {
      setError(TEMPERATURE_UNREADABLE_HINT)
      return
    }
    setError(null)
    setSubmitting(true)
    const request = {
      displayName: draft.displayName.trim(),
      baseUrl: draft.baseUrl.trim(),
      modelIdentifier: draft.modelIdentifier.trim(),
      temperature: resolveTemperature(),
      maxTokens: Number(draft.maxTokens),
      apiKey: resolveApiKey(),
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

  const idleHint =
    draft.baseUrl.trim() === ''
      ? null
      : preset?.needsKey && !typedKey && !model?.apiKeySet
        ? 'Zugangsschlüssel eintragen, dann „Modelle abrufen“.'
        : preset?.placeholderHost
          ? 'Host anpassen, dann „Modelle abrufen“ oder Enter.'
          : '„Modelle abrufen“ zeigt, welche Modelle der Endpunkt anbietet.'

  return (
    <Dialog open={open} onClose={close} maxWidth="sm" fullWidth>
      <DialogTitle>{titel}</DialogTitle>
      <DialogContent>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}
        <Stack spacing={3.5} sx={{ mt: 1 }}>
          <Box component="section" aria-labelledby="llm-model-endpoint-head">
            <SectionHead component="h3" id="llm-model-endpoint-head">
              1 · Endpunkt
            </SectionHead>
            <Stack
              direction={{ xs: 'column', sm: 'row' }}
              spacing={1}
              sx={{ alignItems: { xs: 'stretch', sm: 'flex-start' } }}
            >
              <BaseUrlField
                value={draft.baseUrl}
                inputRef={addressRef}
                onType={changeBaseUrl}
                onChoose={choosePreset}
                onSubmit={() => runDiscovery()}
              />
              <BusyButton
                variant="outlined"
                busy={discovery.state.status === 'loading'}
                busyAnnouncement="Modelle werden abgerufen"
                disabled={draft.baseUrl.trim() === ''}
                onClick={() => runDiscovery()}
                sx={{ flexShrink: 0, height: 40, whiteSpace: 'nowrap' }}
              >
                Modelle abrufen
              </BusyButton>
            </Stack>
            <TextField
              label="API-Schlüssel (optional)"
              type="password"
              value={apiKeyInput}
              disabled={apiKeyClearRequested}
              onChange={(e) => {
                setApiKeyTouched(true)
                setApiKeyClearRequested(false)
                setApiKeyInput(e.target.value)
              }}
              helperText={
                apiKeyClearRequested
                  ? 'Der gespeicherte Schlüssel wird beim Speichern entfernt.'
                  : isEdit
                    ? 'Leer lassen, um den gespeicherten Schlüssel unverändert zu lassen.'
                    : preset?.needsKey
                      ? 'Wird verschlüsselt gespeichert und nie wieder angezeigt.'
                      : 'Lokal betriebene Modellserver brauchen meist keinen.'
              }
              size="small"
              fullWidth
              autoComplete="new-password"
              sx={{ mt: 2 }}
            />
            {model?.apiKeySet && (
              <Box sx={{ mt: 0.5 }}>
                {apiKeyClearRequested ? (
                  <Button size="small" onClick={() => setApiKeyClearRequested(false)}>
                    Entfernen rückgängig machen
                  </Button>
                ) : (
                  <Button
                    size="small"
                    color="error"
                    onClick={() => {
                      setApiKeyClearRequested(true)
                      setApiKeyTouched(false)
                      setApiKeyInput('')
                    }}
                    aria-label={`Gespeicherten Schlüssel von "${model.displayName}" entfernen`}
                  >
                    Gespeicherten Schlüssel entfernen
                  </Button>
                )}
              </Box>
            )}
            <DiscoveryStatus state={discovery.state} idleHint={idleHint} />
          </Box>

          <Box component="section" aria-labelledby="llm-model-model-head">
            <SectionHead component="h3" id="llm-model-model-head">
              2 · Modell
            </SectionHead>
            <ModelIdentifierField
              value={draft.modelIdentifier}
              models={discovery.models}
              loading={discovery.state.status === 'loading'}
              onInputChange={(modelIdentifier) => {
                setDraft((current) => ({ ...current, modelIdentifier }))
                setContextLength(null)
              }}
              onSelect={selectModel}
            />
            {testing && (
              <Typography role="status" sx={{ mt: 1, fontSize: 12.5, color: 'text.secondary' }}>
                Verbindung wird getestet …
              </Typography>
            )}
            {testResult && (
              <Alert severity={testResult.success ? 'success' : 'error'} sx={{ mt: 1.5 }}>
                {testResult.message}
              </Alert>
            )}
          </Box>

          <Box component="section" aria-labelledby="llm-model-display-head">
            <SectionHead component="h3" id="llm-model-display-head">
              3 · Anzeige und Antwort
            </SectionHead>
            <Stack spacing={2}>
              <TextField
                label="Anzeigename"
                required
                value={draft.displayName}
                onChange={(e) => setDraft({ ...draft, displayName: e.target.value })}
                helperText="So erscheint das Modell in der Liste."
                size="small"
                fullWidth
              />
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
                <TextField
                  id={temperatureFieldId}
                  label="Temperatur (optional)"
                  type="number"
                  value={draft.temperature}
                  onChange={(e) => {
                    // An unreadable number (such as „0,7“ depending on the browser) yields an
                    // empty value as well; it must not be silently saved as „keine Temperatur“.
                    setTemperatureUnreadable(e.target.validity.badInput)
                    setDraft({ ...draft, temperature: e.target.value })
                  }}
                  error={temperatureUnreadable}
                  helperText={
                    temperatureUnreadable ? TEMPERATURE_UNREADABLE_HINT : TEMPERATURE_HELP_TEXT
                  }
                  slotProps={{ htmlInput: { min: 0, max: 2, step: 0.1 } }}
                  size="small"
                  sx={{ flex: 1 }}
                />
                <TextField
                  label="Maximale Antwortlänge (Token)"
                  required
                  type="number"
                  value={draft.maxTokens}
                  onChange={(e) => {
                    setMaxTokensTouched(true)
                    setDraft({ ...draft, maxTokens: e.target.value })
                  }}
                  helperText={
                    contextLength
                      ? `Kontextfenster des Modells: ${formatTokens(contextLength)} Token. Vorgeschlagen ist höchstens die Hälfte davon.`
                      : undefined
                  }
                  slotProps={{ htmlInput: { min: 1, max: contextLength ?? undefined } }}
                  size="small"
                  sx={{ flex: 1 }}
                />
              </Stack>
            </Stack>
          </Box>
          {!isValid() && (
            <Typography id={requiredHintId} variant="caption" sx={{ color: 'text.secondary' }}>
              {REQUIRED_FIELDS_HINT}
            </Typography>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button
          onClick={() => void handleTest()}
          disabled={
            testing ||
            temperatureUnreadable ||
            draft.baseUrl.trim() === '' ||
            draft.modelIdentifier.trim() === ''
          }
        >
          {testing ? 'Verbindung wird getestet …' : 'Verbindung testen'}
        </Button>
        <Box sx={{ flex: 1 }} />
        <Button onClick={close} disabled={submitting}>
          Abbrechen
        </Button>
        <Button
          variant="contained"
          onClick={() => void handleSubmit()}
          disabled={submitting || !isValid() || temperatureUnreadable}
          aria-describedby={
            !isValid() ? requiredHintId : temperatureUnreadable ? temperatureHelperId : undefined
          }
        >
          {isEdit ? 'Speichern' : 'Anlegen'}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
