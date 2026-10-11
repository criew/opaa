import { useRef, useState, type ReactNode } from 'react'
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
import CloudOutlinedIcon from '@mui/icons-material/CloudOutlined'
import ComputerOutlinedIcon from '@mui/icons-material/ComputerOutlined'
import DnsOutlinedIcon from '@mui/icons-material/DnsOutlined'
import LinkOutlinedIcon from '@mui/icons-material/LinkOutlined'
import type { DiscoveredLlmModel, LlmModelResponse } from '../../../types/api'
import { testLlmModel } from '../../../services/modelApi'
import { useLlmModelStore } from '../../../stores/llmModelStore'
import BusyButton from '../../a11y/BusyButton'
import ChoiceTileGroup from '../../choice/ChoiceTileGroup'
import SectionHead from '../../SectionHead'
import DiscoveryStatus from './DiscoveryStatus'
import ModelIdentifierField from './ModelIdentifierField'
import {
  displayNameFor,
  ENDPOINT_PRESETS,
  formatTokens,
  DEFAULT_MAX_TOKENS,
  presetOf,
  suggestedMaxTokens,
  type EndpointPresetKey,
} from './modelDiscovery'
import { useModelDiscovery } from './useModelDiscovery'

export const BASE_URL_HELP_TEXT =
  'Der OpenAI-kompatible Endpunkt der Modellschnittstelle. Auch lokal betriebene Modellserver ' +
  'bedienen diese Schnittstelle – etwa Ollama, mit angehängtem „/v1“.'

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

const PRESET_ICONS: Record<EndpointPresetKey, ReactNode> = {
  'ollama-host': <ComputerOutlinedIcon fontSize="small" />,
  'ollama-compose': <DnsOutlinedIcon fontSize="small" />,
  openai: <CloudOutlinedIcon fontSize="small" />,
  custom: <LinkOutlinedIcon fontSize="small" />,
}

const PRESET_TILES = ENDPOINT_PRESETS.map((preset) => ({
  value: preset.key,
  label: preset.label,
  description: preset.description,
  icon: PRESET_ICONS[preset.key],
}))

/**
 * Das Formular eines Chat-Modells (#759, #1621) — dasselbe für Anlegen und Bearbeiten, wie
 * `UserFormDialog` unter „Benutzer".
 *
 * Geführt in drei Abschnitten (#2488): Endpunkt (Vorlage oder eigene Adresse, Schlüssel,
 * „Modelle abrufen"), Modell (Auswahl aus der Liste des Endpunkts oder freie Eingabe) und Anzeige.
 * Eine Auswahl belegt Anzeigename und Antwortlänge vor, solange sie nicht von Hand gesetzt
 * wurden, und testet die Verbindung sofort.
 *
 * Kein Anbieter-Auswahlfeld: Die Schnittstelle spricht ausschließlich das OpenAI-kompatible
 * Protokoll; die Vorlagen sind nur Adressen.
 *
 * Der Aufrufer gibt dem Dialog einen `key`, damit ein Wechsel des Modells den Entwurf neu setzt —
 * MUI unmountet beim Schließen nur die Kinder, nicht die Komponente.
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
  // #759 review: eine ausdrückliche Anforderung statt „leeres Feld heißt löschen" - das Feld
  // startet bei jedem Modell leer, weil der Schlüssel nie zurückgelesen wird; unberührt-leer und
  // „bitte entfernen" wären sonst nicht zu unterscheiden.
  const [apiKeyClearRequested, setApiKeyClearRequested] = useState(false)
  const [temperatureUnreadable, setTemperatureUnreadable] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [testResult, setTestResult] = useState<{ success: boolean; message: string } | null>(null)
  const [testing, setTesting] = useState(false)
  // Vorbelegung überschreibt nur, was die Person nicht selbst gesetzt hat.
  const [suggestedName, setSuggestedName] = useState<string | null>(null)
  const [maxTokensTouched, setMaxTokensTouched] = useState(false)
  const [contextLength, setContextLength] = useState<number | null>(null)
  const discovery = useModelDiscovery()
  const addressRef = useRef<HTMLInputElement>(null)

  const isEdit = model !== null
  const titel = isEdit ? `„${model.displayName}“ bearbeiten` : 'Chat-Modell anlegen'
  const requiredHintId = 'llm-model-form-required-hint'
  const preset = presetOf(draft.baseUrl)
  const typedKey = apiKeyTouched && apiKeyInput.trim() !== ''
  const temperatureFieldId = 'llm-model-form-temperature'
  // MUI vergibt der Hilfezeile eines TextField mit `id` die ID `<id>-helper-text`.
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
   * Löst auf, was für `apiKey` zu senden ist — die Dreiwege-Konvention von `LlmModelRequest`:
   * weggelassen heißt unverändert, leerer Text heißt entfernen, alles andere heißt setzen.
   *
   * Verbindungstest und Modellermittlung bekommen denselben Wert, obwohl der leere Text dort etwas
   * anderes bedeutet: Sie speichern nie etwas, und ein weggelassener Schlüssel lässt sie den
   * **gespeicherten** Schlüssel dieses Modells weiterverwenden — nur für dessen eigene Adresse.
   */
  function resolveApiKey(): string | undefined {
    if (apiKeyClearRequested) return ''
    if (apiKeyTouched) return apiKeyInput.trim()
    return undefined
  }

  /** Ein leeres Feld heißt „keine Angabe“: Der Parameter geht dann gar nicht an das Modell. */
  function resolveTemperature(): number | null {
    return draft.temperature.trim() === '' ? null : Number(draft.temperature)
  }

  function close() {
    if (submitting) return
    onClose()
  }

  /** A new address invalidates the list and the last test result; both belonged to the old one. */
  function changeBaseUrl(baseUrl: string) {
    setDraft((current) => ({ ...current, baseUrl }))
    discovery.reset()
    setTestResult(null)
  }

  function runDiscovery(baseUrl = draft.baseUrl) {
    if (baseUrl.trim() === '') return
    void discovery.discover({
      baseUrl: baseUrl.trim(),
      apiKey: resolveApiKey(),
      modelId: model && !apiKeyClearRequested ? model.id : undefined,
    })
  }

  function choosePreset(key: EndpointPresetKey) {
    const chosen = ENDPOINT_PRESETS.find((candidate) => candidate.key === key)!
    if (chosen.baseUrl === null) {
      if (preset !== 'custom') changeBaseUrl('')
      addressRef.current?.focus()
      return
    }
    changeBaseUrl(chosen.baseUrl)
    if (chosen.keyless || typedKey) runDiscovery(chosen.baseUrl)
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
    setTestResult(null)
    setTesting(true)
    try {
      const result = await testLlmModel({
        baseUrl: draft.baseUrl.trim(),
        modelIdentifier: modelIdentifier.trim(),
        apiKey: resolveApiKey(),
        modelId: model?.id,
        temperature: resolveTemperature(),
      })
      setTestResult(result)
    } catch (err) {
      setTestResult({
        success: false,
        message: err instanceof Error ? err.message : 'Verbindungstest fehlgeschlagen',
      })
    } finally {
      setTesting(false)
    }
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
      ? 'Vorlage wählen oder Adresse eintragen, dann „Modelle abrufen“. Die Kennung lässt sich auch direkt eintragen.'
      : preset === 'openai' && !typedKey && !model?.apiKeySet
        ? 'Zugangsschlüssel eintragen, dann „Modelle abrufen“.'
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
            <Typography id="llm-model-endpoint-question" variant="body2" sx={{ mb: 1.5 }}>
              Wo läuft das Modell?
            </Typography>
            <ChoiceTileGroup<EndpointPresetKey>
              aria-labelledby="llm-model-endpoint-question"
              value={preset}
              onChange={choosePreset}
              tiles={PRESET_TILES}
            />
            <Stack
              direction={{ xs: 'column', sm: 'row' }}
              spacing={1}
              sx={{ mt: 2.5, alignItems: { xs: 'stretch', sm: 'flex-start' } }}
            >
              <TextField
                label="Basis-Adresse"
                required
                value={draft.baseUrl}
                inputRef={addressRef}
                onChange={(e) => changeBaseUrl(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') {
                    e.preventDefault()
                    runDiscovery()
                  }
                }}
                helperText={BASE_URL_HELP_TEXT}
                slotProps={{ htmlInput: { spellCheck: false } }}
                size="small"
                fullWidth
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
                    : preset === 'openai'
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
                    // Eine unlesbare Zahl (etwa „0,7“ je nach Browser) liefert ebenfalls einen
                    // leeren Wert; sie darf nicht still als „keine Temperatur“ gespeichert werden.
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
