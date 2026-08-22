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
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import type { LlmModelResponse } from '../types/api'
import { testLlmModel } from '../services/api'
import { useAuthStore } from '../stores/authStore'
import { useLlmModelStore } from '../stores/llmModelStore'
import PageHeading from '../components/a11y/PageHeading'
import CreateLlmModelDialog from '../components/admin/CreateLlmModelDialog'

const BASE_URL_HELP_TEXT =
  'Der OpenAI-kompatible Endpunkt der Modellschnittstelle. Auch lokal betriebene Modellserver ' +
  'bedienen diese Schnittstelle – etwa Ollama, mit angehängtem „/v1“.'

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
  const [localError, setLocalError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [activating, setActivating] = useState(false)
  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState<{ success: boolean; message: string } | null>(null)

  function isValid() {
    return (
      draft.displayName.trim() !== '' &&
      draft.baseUrl.trim() !== '' &&
      draft.modelIdentifier.trim() !== '' &&
      draft.temperature.trim() !== '' &&
      draft.maxTokens.trim() !== ''
    )
  }

  async function handleSave() {
    if (!isValid()) {
      setLocalError(
        'Anzeigename, Basis-Adresse, Modell-Kennung, Temperatur und maximale Antwortlänge sind erforderlich.',
      )
      return
    }
    setLocalError(null)
    setSaving(true)
    try {
      await updateExistingModel(model.id, {
        displayName: draft.displayName.trim(),
        baseUrl: draft.baseUrl.trim(),
        modelIdentifier: draft.modelIdentifier.trim(),
        temperature: Number(draft.temperature),
        maxTokens: Number(draft.maxTokens),
        apiKey: apiKeyTouched ? apiKeyInput.trim() : undefined,
      })
      setApiKeyInput('')
      setApiKeyTouched(false)
    } catch (err) {
      setLocalError(err instanceof Error ? err.message : 'Aktualisierung fehlgeschlagen')
    } finally {
      setSaving(false)
    }
  }

  async function handleDelete() {
    if (
      !window.confirm(
        `Modell "${model.displayName}" löschen? Diese Aktion kann nicht rückgängig gemacht werden.`,
      )
    ) {
      return
    }
    setLocalError(null)
    try {
      await deleteExistingModel(model.id)
    } catch (err) {
      setLocalError(err instanceof Error ? err.message : 'Löschen fehlgeschlagen')
    }
  }

  async function handleActivate() {
    setLocalError(null)
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
    setTesting(true)
    try {
      const result = await testLlmModel({
        baseUrl: draft.baseUrl.trim(),
        modelIdentifier: draft.modelIdentifier.trim(),
        apiKey: apiKeyTouched ? apiKeyInput.trim() : undefined,
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

        <Stack spacing={2} sx={{ mb: 2 }}>
          <TextField
            label="Anzeigename"
            value={draft.displayName}
            onChange={(e) => setDraft({ ...draft, displayName: e.target.value })}
            size="small"
            fullWidth
          />
          <TextField
            label="Basis-Adresse"
            value={draft.baseUrl}
            onChange={(e) => setDraft({ ...draft, baseUrl: e.target.value })}
            helperText={BASE_URL_HELP_TEXT}
            size="small"
            fullWidth
          />
          <TextField
            label="Modell-Kennung"
            value={draft.modelIdentifier}
            onChange={(e) => setDraft({ ...draft, modelIdentifier: e.target.value })}
            size="small"
            fullWidth
          />
          <Stack direction="row" spacing={2}>
            <TextField
              label="Temperatur"
              type="number"
              value={draft.temperature}
              onChange={(e) => setDraft({ ...draft, temperature: e.target.value })}
              slotProps={{ htmlInput: { min: 0, max: 2, step: 0.1 } }}
              size="small"
              sx={{ flex: 1 }}
            />
            <TextField
              label="Maximale Antwortlänge (Token)"
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
            onChange={(e) => {
              setApiKeyTouched(true)
              setApiKeyInput(e.target.value)
            }}
            helperText={
              model.apiKeySet
                ? 'Ein Schlüssel ist hinterlegt. Leer lassen, um ihn unverändert zu lassen; ' +
                  'zum Entfernen leer speichern.'
                : 'Kein Schlüssel hinterlegt. Lokale Endpunkte laufen häufig ohne ' +
                  'Authentifizierung - leer lassen, wenn kein Schlüssel benötigt wird.'
            }
            size="small"
            fullWidth
            autoComplete="new-password"
          />

          <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap' }}>
            <Button
              variant="contained"
              size="small"
              onClick={() => void handleSave()}
              disabled={saving || !isValid()}
            >
              Speichern
            </Button>
            <Button
              size="small"
              onClick={() => void handleTest()}
              disabled={
                testing || draft.baseUrl.trim() === '' || draft.modelIdentifier.trim() === ''
              }
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
            <Tooltip
              title={
                model.active
                  ? 'Das aktive Modell kann nicht gelöscht werden - zuerst ein anderes Modell aktivieren.'
                  : ''
              }
            >
              <span>
                <Button
                  color="error"
                  size="small"
                  onClick={() => void handleDelete()}
                  disabled={model.active}
                >
                  Modell löschen
                </Button>
              </span>
            </Tooltip>
          </Stack>
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
            // Keyed on updatedAt, not just id (#759 lint fix): the store always reloads the full
            // list after save/activate rather than patching one entry in place, so a fresh
            // updatedAt is the signal that the card should remount with the server's latest
            // values instead of syncing its draft state from a prop change inside an effect.
            <LlmModelCard key={`${model.id}-${model.updatedAt}`} model={model} />
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
