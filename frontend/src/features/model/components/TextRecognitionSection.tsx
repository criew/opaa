import { useEffect, useId, useState, type ReactNode } from 'react'
import { AxiosError } from 'axios'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import FormControl from '@mui/material/FormControl'
import FormControlLabel from '@mui/material/FormControlLabel'
import FormLabel from '@mui/material/FormLabel'
import Link from '@mui/material/Link'
import Radio from '@mui/material/Radio'
import RadioGroup from '@mui/material/RadioGroup'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import DocumentScannerOutlinedIcon from '@mui/icons-material/DocumentScannerOutlined'
import ImageSearchOutlinedIcon from '@mui/icons-material/ImageSearchOutlined'
import visuallyHidden from '@mui/utils/visuallyHidden'
import type {
  SearchModelRoleState,
  TextRecognitionBinding,
  TextRecognitionSettingsResponse,
  TextRecognitionTestResponse,
} from '@/shared/api/types'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import ChoiceTileGroup from '@/shared/ui/choice/ChoiceTileGroup'
import DialogTitleWithClose from '@/shared/ui/DialogTitleWithClose'
import StateDot from '@/shared/ui/StateDot'
import type { StatusTone } from '@/shared/ui/StatusLine'
import ValueRow from '@/shared/ui/ValueRow'
import { notify } from '@/shared/stores/notificationStore'
import { currentUiLabVariant } from '@/shared/uiLabVariant'
import {
  getTextRecognitionSettings,
  testTextRecognition,
  updateTextRecognitionSettings,
} from '../api/modelApi'

/**
 * UI lab of epic #2640, surface (a): the tab „Texterkennung“ of the model administration in three
 * variants. A is one form like „Reranking“, B a summary with the connection in a dialog, C a
 * guided form with the binding as tiles and a staged connection test. All three share
 * {@link useTextRecognitionForm}; the operation switch off shows only the notice.
 */
export default function TextRecognitionSection() {
  const form = useTextRecognitionForm()
  const variant = currentUiLabVariant()

  if (form.loadError) return <Alert severity="error">{form.loadError}</Alert>
  if (!form.settings) {
    return (
      <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
        Einstellungen werden geladen …
      </Typography>
    )
  }
  if (!form.settings.operationEnabled) return <OperationOffNotice settings={form.settings} />
  if (variant === 'B') return <SummaryVariant form={form} />
  if (variant === 'C') return <GuidedVariant form={form} />
  return <FormVariant form={form} />
}

const INTRO =
  'Die Texterkennung liest gescannte PDFs ohne Textebene, damit sie durchsuchbar und zitierfähig werden. Ab Werk ist sie aus.'

const BINDINGS: Record<
  TextRecognitionBinding,
  { label: string; description: string; addressHint: string; example: string }
> = {
  DOCLING_SERVE: {
    label: 'docling-serve',
    description: 'Der mitgelieferte Dienst opaa-ocr oder ein eigenes docling-serve.',
    addressHint: 'Ohne den Pfad /v1, etwa http://opaa-ocr:5001.',
    example: 'http://opaa-ocr:5001',
  },
  OPENAI_VISION: {
    label: 'Vision-Modell',
    description: 'Ein OpenAI-kompatibles Modell mit Bildeingang, etwa PaddleOCR-VL.',
    addressHint: 'Mit dem Pfad /v1, etwa http://vllm:8000/v1.',
    example: 'http://vllm:8000/v1',
  },
}

const VISION_NOTICE =
  'Mit einem Vision-Modell springt „Dokument öffnen“ nur auf die Seite der Fundstelle, ohne Rahmen.'

const STATE: Record<SearchModelRoleState, { tone: StatusTone; word: string; text: string }> = {
  DISABLED: { tone: 'neutral', word: 'Aus', text: 'Ausgeschaltet.' },
  ACTIVE: { tone: 'success', word: 'Aktiv', text: 'Eingeschaltet, der Dienst antwortet.' },
  UNCONFIGURED: {
    tone: 'warning',
    word: 'Unvollständig',
    text: 'Eingeschaltet, aber Adresse oder Modell fehlen.',
  },
  UNREACHABLE: {
    tone: 'error',
    word: 'Nicht erreichbar',
    text: 'Eingeschaltet, aber der Dienst antwortet nicht.',
  },
}

function OperationOffNotice({ settings }: { settings: TextRecognitionSettingsResponse }) {
  return (
    <Box sx={{ maxWidth: '72ch' }}>
      <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mb: 2 }}>{INTRO}</Typography>
      <Alert severity="info">
        Der Betrieb hat die Texterkennung nicht freigegeben (OPAA_OCR_ENABLED). Gescannte PDFs
        werden weiter abgewiesen.
        {settings.baseUrl ? ' Die gespeicherte Anbindung bleibt erhalten.' : ''}
      </Alert>
    </Box>
  )
}

// ---------------------------------------------------------------------------------------------
// Shared form state

interface Draft {
  enabled: boolean
  binding: TextRecognitionBinding
  baseUrl: string
  model: string
  apiKey: string
  apiKeyTouched: boolean
  apiKeyClearRequested: boolean
}

function draftOf(stored: TextRecognitionSettingsResponse): Draft {
  return {
    enabled: stored.enabled,
    binding: stored.binding,
    baseUrl: stored.baseUrl ?? '',
    model: stored.model ?? '',
    apiKey: '',
    apiKeyTouched: false,
    apiKeyClearRequested: false,
  }
}

/** The field errors of a 400, by the field name after the role prefix (`textRecognition.baseUrl`). */
function fieldErrorsOf(err: unknown): Record<string, string> {
  const cause = err instanceof Error ? err.cause : undefined
  if (!(cause instanceof AxiosError)) return {}
  const data = cause.response?.data as
    { fieldErrors?: { field: string; message: string }[] } | undefined
  const result: Record<string, string> = {}
  for (const error of data?.fieldErrors ?? []) {
    result[error.field.substring(error.field.indexOf('.') + 1)] ??= error.message
  }
  return result
}

type TestResult = TextRecognitionTestResponse | { blocked: string }

function useTextRecognitionForm() {
  const [settings, setSettings] = useState<TextRecognitionSettingsResponse | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [draft, setDraft] = useState<Draft | null>(null)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState<TestResult | null>(null)

  useEffect(() => {
    let current = true
    getTextRecognitionSettings()
      .then((stored) => {
        if (!current) return
        setSettings(stored)
        setDraft(draftOf(stored))
      })
      .catch((err: unknown) => {
        if (current) setLoadError(err instanceof Error ? err.message : 'Laden fehlgeschlagen')
      })
    return () => {
      current = false
    }
  }, [])

  /** Changes a connection field: the last test result no longer applies (P33). */
  function change(patch: Partial<Draft>, connection = true) {
    setDraft((d) => (d ? { ...d, ...patch } : d))
    if (connection) setTestResult(null)
    setErrors((e) => {
      const next = { ...e }
      for (const key of Object.keys(patch)) delete next[key]
      return next
    })
  }

  function resolveApiKey(d: Draft): string | undefined {
    if (d.apiKeyClearRequested) return ''
    if (d.apiKeyTouched && d.apiKey.trim() !== '') return d.apiKey.trim()
    return undefined
  }

  async function save(override?: Partial<Draft>): Promise<boolean> {
    if (!draft) return false
    const d = { ...draft, ...override }
    setSaving(true)
    setErrors({})
    setError(null)
    try {
      const saved = await updateTextRecognitionSettings({
        enabled: d.enabled,
        binding: d.binding,
        baseUrl: d.baseUrl.trim(),
        model: d.binding === 'OPENAI_VISION' ? d.model.trim() : null,
        apiKey: resolveApiKey(d),
      })
      setSettings(saved)
      setDraft(draftOf(saved))
      notify('Die Texterkennung wurde gespeichert.', 'success')
      return true
    } catch (err) {
      const fields = fieldErrorsOf(err)
      setErrors(fields)
      setError(
        Object.keys(fields).length > 0
          ? null
          : err instanceof Error
            ? err.message
            : 'Speichern fehlgeschlagen',
      )
      return false
    } finally {
      setSaving(false)
    }
  }

  async function test() {
    if (!draft) return
    if (draft.baseUrl.trim() === '') {
      setErrors((e) => ({ ...e, baseUrl: 'Die Adresse fehlt.' }))
      return
    }
    setTesting(true)
    setTestResult(null)
    try {
      setTestResult(
        await testTextRecognition({
          binding: draft.binding,
          baseUrl: draft.baseUrl.trim(),
          model: draft.binding === 'OPENAI_VISION' ? draft.model.trim() : null,
          apiKey: resolveApiKey(draft),
        }),
      )
    } catch (err) {
      const fields = fieldErrorsOf(err)
      if (fields.baseUrl) {
        setErrors((e) => ({ ...e, baseUrl: fields.baseUrl }))
        setTestResult({ blocked: fields.baseUrl })
      } else {
        setTestResult({
          success: false,
          outcome: 'UNREACHABLE',
          message: err instanceof Error ? err.message : 'Verbindungstest fehlgeschlagen',
        })
      }
    } finally {
      setTesting(false)
    }
  }

  return {
    settings,
    loadError,
    draft,
    errors,
    error,
    saving,
    testing,
    testResult,
    change,
    save,
    test,
    reset: () => {
      if (settings) setDraft(draftOf(settings))
      setErrors({})
      setError(null)
      setTestResult(null)
    },
  }
}

type Form = ReturnType<typeof useTextRecognitionForm>

// ---------------------------------------------------------------------------------------------
// Fields used by more than one variant

function AddressField({ form, id }: { form: Form; id: string }) {
  const d = form.draft!
  return (
    <TextField
      id={id}
      label="Adresse"
      value={d.baseUrl}
      placeholder={BINDINGS[d.binding].example}
      onChange={(e) => form.change({ baseUrl: e.target.value })}
      error={Boolean(form.errors.baseUrl)}
      helperText={form.errors.baseUrl ?? BINDINGS[d.binding].addressHint}
      size="small"
      fullWidth
    />
  )
}

function ModelField({ form, id }: { form: Form; id: string }) {
  const d = form.draft!
  if (d.binding !== 'OPENAI_VISION') return null
  return (
    <TextField
      id={id}
      label="Modell-Kennung"
      value={d.model}
      placeholder="PaddlePaddle/PaddleOCR-VL"
      onChange={(e) => form.change({ model: e.target.value })}
      error={Boolean(form.errors.model)}
      helperText={form.errors.model}
      size="small"
      fullWidth
    />
  )
}

/** Variant A: the key field of the other roles - empty keeps, a button removes. */
function KeyFieldPlain({ form, id }: { form: Form; id: string }) {
  const d = form.draft!
  const stored = form.settings!.apiKeySet
  return (
    <Box>
      <TextField
        id={id}
        label="Schlüssel (optional)"
        type="password"
        value={d.apiKey}
        disabled={d.apiKeyClearRequested}
        onChange={(e) =>
          form.change({ apiKey: e.target.value, apiKeyTouched: true, apiKeyClearRequested: false })
        }
        error={Boolean(form.errors.apiKey)}
        helperText={
          form.errors.apiKey ??
          (d.apiKeyClearRequested
            ? 'Der gespeicherte Schlüssel wird beim Speichern entfernt.'
            : stored
              ? 'Leer lassen, um den gespeicherten Schlüssel zu behalten.'
              : 'docling-serve: X-Api-Key, Vision-Modell: Bearer.')
        }
        size="small"
        fullWidth
        autoComplete="new-password"
      />
      {stored && (
        <Button
          size="small"
          color={d.apiKeyClearRequested ? 'primary' : 'error'}
          sx={{ mt: 0.5 }}
          onClick={() =>
            form.change({
              apiKeyClearRequested: !d.apiKeyClearRequested,
              apiKeyTouched: false,
              apiKey: '',
            })
          }
        >
          {d.apiKeyClearRequested
            ? 'Entfernen rückgängig machen'
            : 'Gespeicherten Schlüssel entfernen'}
        </Button>
      )}
    </Box>
  )
}

/** Variants B and C: a stored key reads „Gesetzt“ with „Ersetzen“ (guidelines 5.2, P34). */
function KeyFieldSet({ form, id }: { form: Form; id: string }) {
  const d = form.draft!
  const stored = form.settings!.apiKeySet && !d.apiKeyClearRequested
  const [replacing, setReplacing] = useState(false)
  if (stored && !replacing) {
    return (
      <Box>
        <Typography component="div" sx={{ fontSize: 12, color: 'text.secondary', mb: 0.5 }}>
          Schlüssel
        </Typography>
        <Box sx={{ display: 'flex', gap: 2, alignItems: 'baseline', flexWrap: 'wrap' }}>
          <Typography sx={{ fontSize: 13.5 }}>Gesetzt</Typography>
          <Link component="button" type="button" onClick={() => setReplacing(true)}>
            Ersetzen
          </Link>
          <Link
            component="button"
            type="button"
            onClick={() =>
              form.change({ apiKeyClearRequested: true, apiKeyTouched: false, apiKey: '' })
            }
          >
            Entfernen
          </Link>
        </Box>
      </Box>
    )
  }
  return (
    <TextField
      id={id}
      label="Schlüssel (optional)"
      type="password"
      value={d.apiKey}
      onChange={(e) => form.change({ apiKey: e.target.value, apiKeyTouched: true })}
      error={Boolean(form.errors.apiKey)}
      helperText={
        form.errors.apiKey ??
        (d.apiKeyClearRequested
          ? 'Der gespeicherte Schlüssel wird beim Speichern entfernt.'
          : 'Wird verschlüsselt gespeichert und nie wieder angezeigt.')
      }
      size="small"
      fullWidth
      autoComplete="new-password"
    />
  )
}

function TestResultAlert({ result }: { result: TestResult | null }) {
  if (!result || 'blocked' in result) return null
  return <Alert severity={result.success ? 'success' : 'error'}>{result.message}</Alert>
}

function Buttons({ form, onSave }: { form: Form; onSave?: () => void }) {
  return (
    <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', rowGap: 1 }}>
      <BusyButton
        variant="outlined"
        busy={form.testing}
        busyAnnouncement="Verbindung wird getestet"
        onClick={() => void form.test()}
      >
        Verbindung testen
      </BusyButton>
      <BusyButton
        variant="contained"
        busy={form.saving}
        busyAnnouncement="Wird gespeichert"
        onClick={onSave ?? (() => void form.save())}
      >
        Speichern
      </BusyButton>
    </Stack>
  )
}

function StateLine({ settings }: { settings: TextRecognitionSettingsResponse }) {
  return (
    <Typography role="status" sx={{ fontSize: 13, mb: 2 }}>
      Zustand: {STATE[settings.state].text}
    </Typography>
  )
}

// ---------------------------------------------------------------------------------------------
// Variant A: one form, like „Reranking“ and „Spracheingabe“

function FormVariant({ form }: { form: Form }) {
  const d = form.draft!
  const labelId = useId()
  return (
    <Box sx={{ maxWidth: '72ch' }}>
      <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mb: 2 }}>{INTRO}</Typography>
      <StateLine settings={form.settings!} />
      {form.error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {form.error}
        </Alert>
      )}
      <Stack spacing={2}>
        <FormControlLabel
          control={
            <Switch
              checked={d.enabled}
              onChange={(e) => form.change({ enabled: e.target.checked }, false)}
            />
          }
          label="Texterkennung einschalten"
        />
        <FormControl>
          <FormLabel id={labelId} sx={{ fontSize: 12, mb: 0.5 }}>
            Anbindung
          </FormLabel>
          <RadioGroup
            aria-labelledby={labelId}
            value={d.binding}
            onChange={(e) => form.change({ binding: e.target.value as TextRecognitionBinding })}
          >
            {(Object.keys(BINDINGS) as TextRecognitionBinding[]).map((key) => (
              <FormControlLabel
                key={key}
                value={key}
                control={<Radio size="small" />}
                sx={{ alignItems: 'flex-start', '& .MuiRadio-root': { pt: 0.25 } }}
                label={
                  <Box>
                    <Typography sx={{ fontSize: 13.5, fontWeight: 500 }}>
                      {BINDINGS[key].label}
                    </Typography>
                    <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
                      {BINDINGS[key].description}
                    </Typography>
                  </Box>
                }
              />
            ))}
          </RadioGroup>
        </FormControl>
        <AddressField form={form} id="ocr-a-base-url" />
        <ModelField form={form} id="ocr-a-model" />
        <KeyFieldPlain form={form} id="ocr-a-api-key" />
        {d.binding === 'OPENAI_VISION' && <Alert severity="info">{VISION_NOTICE}</Alert>}
        <TestResultAlert result={form.testResult} />
        <Buttons form={form} />
      </Stack>
    </Box>
  )
}

// ---------------------------------------------------------------------------------------------
// Variant B: summary rows, the connection in a dialog

function SummaryVariant({ form }: { form: Form }) {
  const settings = form.settings!
  const [open, setOpen] = useState(false)
  const configured = Boolean(settings.baseUrl)
  const state = STATE[settings.state]
  const binding = BINDINGS[settings.binding]

  async function toggle(enabled: boolean) {
    await form.save({ enabled })
  }

  return (
    <Box sx={{ maxWidth: '80ch' }}>
      <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mb: 1 }}>{INTRO}</Typography>
      {form.error && !open && (
        <Alert severity="error" sx={{ my: 1 }}>
          {form.error}
        </Alert>
      )}
      {!open && form.errors.baseUrl && (
        <Alert severity="error" sx={{ my: 1 }}>
          {form.errors.baseUrl}
        </Alert>
      )}
      <ValueRow
        title="Zustand"
        value={<StateDot tone={state.tone} label={state.word} />}
        action={
          <Switch
            checked={settings.enabled}
            disabled={form.saving}
            slotProps={{ input: { 'aria-label': 'Texterkennung eingeschaltet' } }}
            onChange={(e) => void toggle(e.target.checked)}
          />
        }
      />
      <ValueRow
        title="Anbindung"
        value={
          configured ? (
            <Box>
              <Typography sx={{ fontSize: 13.5 }}>{binding.label}</Typography>
              <Typography
                sx={{ fontSize: 12.5, color: 'text.secondary', overflowWrap: 'anywhere' }}
              >
                {settings.baseUrl}
                {settings.model ? ` · ${settings.model}` : ''}
                {settings.apiKeySet ? ' · Schlüssel gesetzt' : ' · ohne Schlüssel'}
              </Typography>
            </Box>
          ) : (
            <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
              Noch nicht eingerichtet
            </Typography>
          )
        }
        action={
          <Link component="button" type="button" onClick={() => setOpen(true)}>
            {configured ? 'Ändern' : 'Einrichten'}
          </Link>
        }
      />
      {configured && (
        <ValueRow
          title="Verbindung"
          value={
            form.testResult && !('blocked' in form.testResult) ? (
              <StateDot
                tone={form.testResult.success ? 'success' : 'error'}
                label={form.testResult.message}
              />
            ) : (
              <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
                {form.testing ? 'Wird getestet …' : 'Noch nicht getestet'}
              </Typography>
            )
          }
          action={
            <Link component="button" type="button" onClick={() => void form.test()}>
              Testen
            </Link>
          }
        />
      )}
      {settings.binding === 'OPENAI_VISION' && configured && (
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 1.5 }}>
          {VISION_NOTICE}
        </Typography>
      )}
      <ConnectionDialog
        form={form}
        open={open}
        onClose={() => {
          form.reset()
          setOpen(false)
        }}
        onSaved={() => setOpen(false)}
      />
    </Box>
  )
}

function ConnectionDialog({
  form,
  open,
  onClose,
  onSaved,
}: {
  form: Form
  open: boolean
  onClose: () => void
  onSaved: () => void
}) {
  const d = form.draft
  const titleId = useId()
  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth aria-labelledby={titleId}>
      <DialogTitleWithClose id={titleId} onClose={onClose}>
        Anbindung der Texterkennung
      </DialogTitleWithClose>
      {d && (
        <DialogContent>
          <Stack spacing={2} sx={{ pt: 1 }}>
            <TextField
              select
              id="ocr-b-binding"
              label="Anbindung"
              value={d.binding}
              onChange={(e) => form.change({ binding: e.target.value as TextRecognitionBinding })}
              size="small"
              slotProps={{ select: { native: true } }}
              helperText={BINDINGS[d.binding].description}
            >
              {(Object.keys(BINDINGS) as TextRecognitionBinding[]).map((key) => (
                <option key={key} value={key}>
                  {BINDINGS[key].label}
                </option>
              ))}
            </TextField>
            <AddressField form={form} id="ocr-b-base-url" />
            <ModelField form={form} id="ocr-b-model" />
            <KeyFieldSet form={form} id="ocr-b-api-key" />
            <TestResultAlert result={form.testResult} />
          </Stack>
        </DialogContent>
      )}
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <BusyButton
          variant="outlined"
          busy={form.testing}
          busyAnnouncement="Verbindung wird getestet"
          onClick={() => void form.test()}
        >
          Verbindung testen
        </BusyButton>
        <BusyButton
          variant="contained"
          busy={form.saving}
          busyAnnouncement="Wird gespeichert"
          onClick={() => void form.save().then((ok) => ok && onSaved())}
        >
          Speichern
        </BusyButton>
      </DialogActions>
    </Dialog>
  )
}

// ---------------------------------------------------------------------------------------------
// Variant C: binding as tiles first, staged test, switch at the end

type StageState = 'ok' | 'failed' | 'open'

function stagesOf(result: TestResult | null): { label: string; state: StageState }[] | null {
  if (!result) return null
  const stage = (label: string, state: StageState) => ({ label, state })
  if ('blocked' in result) {
    return [stage('Adresse', 'failed'), stage('Anmeldung', 'open'), stage('Erkennung', 'open')]
  }
  switch (result.outcome) {
    case 'OK':
      return [stage('Adresse', 'ok'), stage('Anmeldung', 'ok'), stage('Erkennung', 'ok')]
    case 'KEY_REJECTED':
      return [stage('Adresse', 'ok'), stage('Anmeldung', 'failed'), stage('Erkennung', 'open')]
    case 'ENGINE_MISSING':
    case 'NO_TEXT':
      return [stage('Adresse', 'ok'), stage('Anmeldung', 'ok'), stage('Erkennung', 'failed')]
    default:
      return [stage('Adresse', 'failed'), stage('Anmeldung', 'open'), stage('Erkennung', 'open')]
  }
}

const STAGE_TONE: Record<StageState, { tone: StatusTone; suffix: string }> = {
  ok: { tone: 'success', suffix: 'geprüft' },
  failed: { tone: 'error', suffix: 'fehlgeschlagen' },
  open: { tone: 'neutral', suffix: 'nicht geprüft' },
}

function StagedResult({ result }: { result: TestResult | null }) {
  const stages = stagesOf(result)
  if (!stages || !result) return null
  const message = 'blocked' in result ? result.blocked : result.message
  return (
    <Box role="status" sx={{ borderTop: 1, borderColor: 'divider', pt: 1.5 }}>
      <Box
        component="ul"
        aria-label="Ergebnis des Verbindungstests"
        sx={{ listStyle: 'none', m: 0, p: 0, display: 'flex', gap: 2.5, flexWrap: 'wrap' }}
      >
        {stages.map((s) => (
          <Box component="li" key={s.label}>
            <StateDot tone={STAGE_TONE[s.state].tone} label={s.label} />
            <Box component="span" sx={visuallyHidden}>
              {` ${STAGE_TONE[s.state].suffix}`}
            </Box>
          </Box>
        ))}
      </Box>
      <Typography sx={{ fontSize: 12.5, mt: 0.75 }}>{message}</Typography>
    </Box>
  )
}

function GuidedVariant({ form }: { form: Form }) {
  const d = form.draft!
  const titleId = useId()
  const tiles = (Object.keys(BINDINGS) as TextRecognitionBinding[]).map((key) => ({
    value: key,
    label: BINDINGS[key].label,
    description: BINDINGS[key].description as ReactNode,
    icon:
      key === 'DOCLING_SERVE' ? (
        <DocumentScannerOutlinedIcon aria-hidden />
      ) : (
        <ImageSearchOutlinedIcon aria-hidden />
      ),
  }))
  return (
    <Box sx={{ maxWidth: '72ch' }}>
      <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mb: 1.5 }}>{INTRO}</Typography>
      <StateLine settings={form.settings!} />
      {form.error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {form.error}
        </Alert>
      )}
      <Stack spacing={2.5}>
        <Box>
          <Typography id={titleId} component="h2" sx={{ fontSize: 15, fontWeight: 600, mb: 1 }}>
            Anbindung
          </Typography>
          <ChoiceTileGroup
            aria-labelledby={titleId}
            tiles={tiles}
            value={d.binding}
            onChange={(binding) => form.change({ binding })}
          />
          {d.binding === 'OPENAI_VISION' && (
            <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 1 }}>
              {VISION_NOTICE}
            </Typography>
          )}
        </Box>
        <Stack spacing={2}>
          <Typography component="h2" sx={{ fontSize: 15, fontWeight: 600 }}>
            Verbindung
          </Typography>
          <AddressField form={form} id="ocr-c-base-url" />
          <ModelField form={form} id="ocr-c-model" />
          <KeyFieldSet form={form} id="ocr-c-api-key" />
          <Box>
            <BusyButton
              variant="outlined"
              busy={form.testing}
              busyAnnouncement="Verbindung wird getestet"
              onClick={() => void form.test()}
            >
              Verbindung testen
            </BusyButton>
          </Box>
          <StagedResult result={form.testResult} />
        </Stack>
        <Box sx={{ borderTop: 1, borderColor: 'divider', pt: 2 }}>
          <FormControlLabel
            control={
              <Switch
                checked={d.enabled}
                onChange={(e) => form.change({ enabled: e.target.checked }, false)}
              />
            }
            label="Texterkennung einschalten"
          />
          <Box sx={{ mt: 1.5 }}>
            <BusyButton
              variant="contained"
              busy={form.saving}
              busyAnnouncement="Wird gespeichert"
              onClick={() => void form.save()}
            >
              Speichern
            </BusyButton>
          </Box>
        </Box>
      </Stack>
    </Box>
  )
}
