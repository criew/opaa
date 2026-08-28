import { useEffect, useState } from 'react'
import Accordion from '@mui/material/Accordion'
import AccordionDetails from '@mui/material/AccordionDetails'
import AccordionSummary from '@mui/material/AccordionSummary'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import Divider from '@mui/material/Divider'
import Paper from '@mui/material/Paper'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import type { LlmModelResponse } from '../types/api'
import { testLlmModel } from '../services/api'
import { useAuthStore } from '../stores/authStore'
import { useLlmModelStore } from '../stores/llmModelStore'
import PageHeading from '../components/a11y/PageHeading'
import GlobalScopeNote from '../components/GlobalScopeNote'
import CreateLlmModelDialog from '../components/admin/CreateLlmModelDialog'

const BASE_URL_HELP_TEXT =
  'Der OpenAI-kompatible Endpunkt der Modellschnittstelle. Auch lokal betriebene Modellserver ' +
  'bedienen diese Schnittstelle – etwa Ollama, mit angehängtem „/v1“.'

const REQUIRED_FIELDS_HINT =
  'Anzeigename, Basis-Adresse, Modell-Kennung, Temperatur und maximale Antwortlänge sind ' +
  'erforderlich.'

interface LlmModelDraft {
  displayName: string
  baseUrl: string
  modelIdentifier: string
  temperature: string
  maxTokens: string
}

function draftFromModel(model: LlmModelResponse): LlmModelDraft {
  return {
    displayName: model.displayName,
    baseUrl: model.baseUrl,
    modelIdentifier: model.modelIdentifier,
    temperature: String(model.temperature),
    maxTokens: String(model.maxTokens),
  }
}

function LlmModelCard({ model }: { model: LlmModelResponse }) {
  const updateExistingModel = useLlmModelStore((s) => s.updateExistingModel)
  const deleteExistingModel = useLlmModelStore((s) => s.deleteExistingModel)
  const activateExistingModel = useLlmModelStore((s) => s.activateExistingModel)

  const [expanded, setExpanded] = useState(false)
  const [draft, setDraft] = useState<LlmModelDraft>(() => draftFromModel(model))
  const [apiKeyInput, setApiKeyInput] = useState('')
  const [apiKeyTouched, setApiKeyTouched] = useState(false)
  // #759 review: a separate, explicit request rather than inferring "remove" from an untouched
  // empty field - the field starts empty for every model (the key is never read back), so an
  // untouched field and "please clear it" were indistinguishable before this flag existed.
  const [apiKeyClearRequested, setApiKeyClearRequested] = useState(false)
  const [localError, setLocalError] = useState<string | null>(null)
  const [savedMessage, setSavedMessage] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [activating, setActivating] = useState(false)
  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState<{ success: boolean; message: string } | null>(null)

  const deleteHintId = `llm-model-${model.id}-delete-hint`
  const requiredHintId = `llm-model-${model.id}-required-hint`

  function isValid() {
    return (
      draft.displayName.trim() !== '' &&
      draft.baseUrl.trim() !== '' &&
      draft.modelIdentifier.trim() !== '' &&
      draft.temperature.trim() !== '' &&
      draft.maxTokens.trim() !== ''
    )
  }

  /**
   * Resolves what to send for `apiKey` following LlmModelRequest's three-way convention (omitted
   * = unchanged, empty string = clear, anything else = set) - shared by save and by the
   * connection test below, even though the empty string means something different for each: on
   * save it clears the stored key, while the connection test never stores anything and an
   * omitted key there instead falls back to reusing the *currently stored* key for this modelId
   * (see LlmModelController#testModel's Javadoc). Passing the resolved value straight through to
   * both keeps that reuse-on-test behaviour working even mid-edit, before anything is saved.
   */
  function resolveApiKeyForRequest(): string | undefined {
    if (apiKeyClearRequested) return ''
    if (apiKeyTouched) return apiKeyInput.trim()
    return undefined
  }

  function resetApiKeyDraft() {
    setApiKeyInput('')
    setApiKeyTouched(false)
    setApiKeyClearRequested(false)
  }

  async function handleSave() {
    if (!isValid()) {
      setLocalError(REQUIRED_FIELDS_HINT)
      return
    }
    setLocalError(null)
    setSavedMessage(null)
    setSaving(true)
    try {
      const updated = await updateExistingModel(model.id, {
        displayName: draft.displayName.trim(),
        baseUrl: draft.baseUrl.trim(),
        modelIdentifier: draft.modelIdentifier.trim(),
        temperature: Number(draft.temperature),
        maxTokens: Number(draft.maxTokens),
        apiKey: resolveApiKeyForRequest(),
      })
      // #759 review: re-seeded from the server's response, not from a remount - the panel stays
      // open, any visible test result is not thrown away, and the confirmation below is the only
      // thing that changes, so focus never leaves the button the person just activated.
      setDraft(draftFromModel(updated))
      resetApiKeyDraft()
      setSavedMessage(`"${updated.displayName}" wurde gespeichert.`)
    } catch (err) {
      setLocalError(err instanceof Error ? err.message : 'Aktualisierung fehlgeschlagen')
    } finally {
      setSaving(false)
    }
  }

  async function handleDelete() {
    if (model.active) return
    if (
      !window.confirm(
        `Modell "${model.displayName}" löschen? Diese Aktion kann nicht rückgängig gemacht werden.`,
      )
    ) {
      return
    }
    setLocalError(null)
    setSavedMessage(null)
    try {
      await deleteExistingModel(model.id)
    } catch (err) {
      setLocalError(err instanceof Error ? err.message : 'Löschen fehlgeschlagen')
    }
  }

  async function handleActivate() {
    setLocalError(null)
    setSavedMessage(null)
    setActivating(true)
    try {
      await activateExistingModel(model.id)
    } catch (err) {
      setLocalError(err instanceof Error ? err.message : 'Aktivierung fehlgeschlagen')
    } finally {
      setActivating(false)
    }
  }

  async function handleTest() {
    setTestResult(null)
    setSavedMessage(null)
    setTesting(true)
    try {
      const result = await testLlmModel({
        baseUrl: draft.baseUrl.trim(),
        modelIdentifier: draft.modelIdentifier.trim(),
        apiKey: resolveApiKeyForRequest(),
        modelId: model.id,
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

  return (
    <Accordion
      expanded={expanded}
      onChange={(_event, isExpanded) => setExpanded(isExpanded)}
      variant="outlined"
      disableGutters
      // Level 2: the cards are the first headings after the page's h1 (the "Einbettungsmodell"
      // h2 follows below them); MUI's default heading element is an h3, which skips a level.
      slotProps={{ heading: { component: 'h2' } }}
      // e2e/README.md, "Selektor-Konvention" (#760): AccordionDetails stays mounted while
      // collapsed (only its height animates), so every card's own "API-Schlüssel" field etc. is
      // simultaneously present in the DOM - a page-wide role/label query cannot tell one model's
      // card apart from another's without this.
      data-testid={`llm-model-card-${model.id}`}
    >
      <AccordionSummary expandIcon={<ExpandMoreIcon />}>
        <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', flexGrow: 1 }}>
          <Typography sx={{ fontSize: 14.5, fontWeight: 600 }}>{model.displayName}</Typography>
          {model.active && (
            <Chip label="Aktiv" color="primary" size="small" aria-label="Aktives Modell" />
          )}
          <Typography sx={{ fontSize: 13, color: 'text.secondary', ml: 'auto', mr: 1 }}>
            {model.modelIdentifier}
          </Typography>
        </Stack>
      </AccordionSummary>
      <AccordionDetails>
        {localError && (
          <Alert severity="error" sx={{ mb: 2 }} onClose={() => setLocalError(null)}>
            {localError}
          </Alert>
        )}
        {testResult && (
          <Alert
            severity={testResult.success ? 'success' : 'error'}
            sx={{ mb: 2 }}
            onClose={() => setTestResult(null)}
          >
            {testResult.message}
          </Alert>
        )}
        {/* #759 review, 2.8: Speichern-Bestätigung ohne Fokuswechsel läuft über eine Live-Region. */}
        <Box role="status" aria-live="polite">
          {savedMessage && (
            <Alert severity="success" sx={{ mb: 2 }} onClose={() => setSavedMessage(null)}>
              {savedMessage}
            </Alert>
          )}
        </Box>

        <Stack spacing={2} sx={{ mb: 2 }}>
          <TextField
            label="Anzeigename"
            required
            value={draft.displayName}
            onChange={(e) => setDraft({ ...draft, displayName: e.target.value })}
            size="small"
            fullWidth
          />
          <TextField
            label="Basis-Adresse"
            required
            value={draft.baseUrl}
            onChange={(e) => setDraft({ ...draft, baseUrl: e.target.value })}
            helperText={BASE_URL_HELP_TEXT}
            size="small"
            fullWidth
          />
          <TextField
            label="Modell-Kennung"
            required
            value={draft.modelIdentifier}
            onChange={(e) => setDraft({ ...draft, modelIdentifier: e.target.value })}
            size="small"
            fullWidth
          />
          <Stack direction="row" spacing={2}>
            <TextField
              label="Temperatur"
              required
              type="number"
              value={draft.temperature}
              onChange={(e) => setDraft({ ...draft, temperature: e.target.value })}
              slotProps={{ htmlInput: { min: 0, max: 2, step: 0.1 } }}
              size="small"
              sx={{ flex: 1 }}
            />
            <TextField
              label="Maximale Antwortlänge (Token)"
              required
              type="number"
              value={draft.maxTokens}
              onChange={(e) => setDraft({ ...draft, maxTokens: e.target.value })}
              slotProps={{ htmlInput: { min: 1 } }}
              size="small"
              sx={{ flex: 1 }}
            />
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
                : 'Leer lassen, um den gespeicherten Schlüssel unverändert zu lassen.'
            }
            size="small"
            fullWidth
            autoComplete="new-password"
          />
          {model.apiKeySet && (
            <Box>
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

          {!isValid() && (
            <Typography id={requiredHintId} variant="caption" color="text.secondary">
              {REQUIRED_FIELDS_HINT}
            </Typography>
          )}

          <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap' }}>
            <Button
              variant="contained"
              size="small"
              onClick={() => void handleSave()}
              disabled={saving || !isValid()}
              aria-describedby={!isValid() ? requiredHintId : undefined}
              aria-label={`"${model.displayName}" speichern`}
            >
              Speichern
            </Button>
            <Button
              size="small"
              onClick={() => void handleTest()}
              disabled={
                testing || draft.baseUrl.trim() === '' || draft.modelIdentifier.trim() === ''
              }
              aria-label={`Verbindung zu "${model.displayName}" testen`}
            >
              {testing ? 'Verbindung wird getestet …' : 'Verbindung testen'}
            </Button>
            {!model.active && (
              <Button
                size="small"
                onClick={() => void handleActivate()}
                disabled={activating}
                aria-label={`"${model.displayName}" als aktives Modell setzen`}
              >
                {activating ? 'Wird aktiviert …' : 'Aktiv setzen'}
              </Button>
            )}
            <Button
              color="error"
              size="small"
              onClick={() => void handleDelete()}
              // Not the native `disabled` attribute (#759 review): a disabled button is pulled out
              // of the tab order in every browser, so a keyboard/screen-reader user could never
              // reach the reason at all - only a mouse hover on a Tooltip would show it.
              // aria-disabled keeps it focusable and announced as disabled; the guard in
              // handleDelete above makes activation a no-op, and the caption below states the
              // reason as visible, programmatically associated text rather than a hover-only one.
              aria-disabled={model.active}
              aria-describedby={model.active ? deleteHintId : undefined}
              sx={model.active ? { opacity: 0.6, cursor: 'not-allowed' } : undefined}
              aria-label={`"${model.displayName}" löschen`}
            >
              Modell löschen
            </Button>
          </Stack>
          {model.active && (
            <Typography id={deleteHintId} variant="caption" color="text.secondary">
              Das aktive Modell kann nicht gelöscht werden - zuerst ein anderes Modell aktivieren.
            </Typography>
          )}
        </Stack>
      </AccordionDetails>
    </Accordion>
  )
}

function EmbeddingInfoSection() {
  const embeddingInfo = useLlmModelStore((s) => s.embeddingInfo)
  const loadEmbeddingInfo = useLlmModelStore((s) => s.loadEmbeddingInfo)

  useEffect(() => {
    void loadEmbeddingInfo()
  }, [loadEmbeddingInfo])

  return (
    <Box sx={{ mt: 4 }}>
      <Typography variant="h6" component="h2" gutterBottom>
        Einbettungsmodell
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        Anders als das Chat-Modell lässt sich das Einbettungsmodell hier nicht ändern: Ein Wechsel
        macht bestehende Vektoren unvergleichbar und würde eine vollständige Neuindizierung aller
        Wissensbibliotheken erfordern.
      </Typography>
      <Paper variant="outlined" sx={{ p: 3 }}>
        {embeddingInfo ? (
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={4}>
            <Box>
              <Typography variant="caption" color="text.secondary" component="div">
                Anbieter
              </Typography>
              <Typography>{embeddingInfo.provider}</Typography>
            </Box>
            <Box>
              <Typography variant="caption" color="text.secondary" component="div">
                Modell
              </Typography>
              <Typography>{embeddingInfo.model}</Typography>
            </Box>
            <Box>
              <Typography variant="caption" color="text.secondary" component="div">
                Dimensionen
              </Typography>
              <Typography>{embeddingInfo.dimensions}</Typography>
            </Box>
          </Stack>
        ) : (
          <Typography color="text.secondary">Einbettungskonfiguration wird geladen …</Typography>
        )}
      </Paper>
    </Box>
  )
}

export default function LlmModelManagementPage() {
  const isSystemAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')
  const models = useLlmModelStore((s) => s.models)
  const isLoading = useLlmModelStore((s) => s.isLoading)
  const error = useLlmModelStore((s) => s.error)
  const loadModels = useLlmModelStore((s) => s.loadModels)
  const [createDialogOpen, setCreateDialogOpen] = useState(false)

  useEffect(() => {
    if (isSystemAdmin) void loadModels()
  }, [isSystemAdmin, loadModels])

  if (!isSystemAdmin) {
    return (
      <Box sx={{ flexGrow: 1, p: 4, maxWidth: 720 }}>
        <PageHeading title="Modelle" gutterBottom />
        <Alert severity="info">
          Die Modellverwaltung wird von der Systemverwaltung gepflegt. Für Ihr Konto ist diese Seite
          nicht freigegeben.
        </Alert>
      </Box>
    )
  }

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 2, mb: 2.5, flexWrap: 'wrap' }}>
        <PageHeading title="Modelle" />
        <Typography component="span" sx={{ fontSize: 13, color: 'text.secondary' }}>
          {models.length === 1 ? '1 Chat-Modell' : `${models.length} Chat-Modelle`}
        </Typography>
        <Button
          variant="contained"
          onClick={() => setCreateDialogOpen(true)}
          sx={{ ml: 'auto', flex: 'none' }}
        >
          Neues Modell
        </Button>
      </Box>
      <GlobalScopeNote />

      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}

      {isLoading ? (
        <Typography color="text.secondary">Modelle werden geladen …</Typography>
      ) : models.length === 0 ? (
        <Typography color="text.secondary">Es sind noch keine Modelle hinterlegt.</Typography>
      ) : (
        <Stack spacing={1}>
          {models.map((model) => (
            // Keyed on id alone (#759 review): a remount on every save/activate/delete reload
            // dropped the open panel, the just-shown test result and the save confirmation right
            // after the action that produced them - see updateExistingModel's own comment for how
            // the card now re-seeds its draft from the server response instead.
            <LlmModelCard key={model.id} model={model} />
          ))}
        </Stack>
      )}

      <Divider sx={{ my: 4 }} />

      <EmbeddingInfoSection />

      <CreateLlmModelDialog
        open={createDialogOpen}
        onClose={() => setCreateDialogOpen(false)}
        onCreated={() => setCreateDialogOpen(false)}
      />
    </Box>
  )
}
