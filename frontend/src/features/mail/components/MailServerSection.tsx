import { useEffect, useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import FormControlLabel from '@mui/material/FormControlLabel'
import MenuItem from '@mui/material/MenuItem'
import Skeleton from '@mui/material/Skeleton'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import visuallyHidden from '@mui/utils/visuallyHidden'
import type {
  MailEncryption,
  MailSendResultResponse,
  MailSettingsResponse,
  MailSettingsUpdateRequest,
} from '@/shared/api/types'
import { useMailStore } from '../stores/mailStore'
import { notify } from '@/shared/stores/notificationStore'
import { apiFieldErrors } from '@/shared/api/apiErrorDetails'
import PageSection from '@/shared/ui/PageSection'
import PublicBaseUrlMissingAlert from '@/shared/ui/smtp/PublicBaseUrlMissingAlert'
import {
  SMTP_ENCRYPTION_OPTIONS,
  SMTP_LABELS,
  SMTP_TEXTS,
  parseSmtpPort,
  smtpTargetChanged,
} from '@/shared/ui/smtp/smtpSettings'
import MailStatusCard from './MailStatusCard'
import { mailSendResultMessage, mailSendResultSeverity } from './mailStatus'

/** Mirrors `MailSettingsService.PASSWORD_MASK`: sending it back means „unverändert". */
const PASSWORD_MASK = '***'

interface FormState {
  enabled: boolean
  host: string
  port: string
  username: string
  password: string
  encryption: MailEncryption
  fromAddress: string
  fromName: string
}

function formOf(settings: MailSettingsResponse): FormState {
  return {
    enabled: settings.enabled,
    host: settings.host ?? '',
    port: settings.port != null ? String(settings.port) : '',
    username: settings.username ?? '',
    // Never the stored value and never the mask: the field starts empty, and an empty field is
    // exactly what „unverändert" looks like.
    password: '',
    encryption: settings.encryption,
    fromAddress: settings.fromAddress ?? '',
    fromName: settings.fromName ?? '',
  }
}

/**
 * The installation's SMTP form (ADR-0033 Entscheidung 10).
 *
 * The password is write-protected in both directions: the response carries only the mask, the
 * field starts empty, and whoever enters nothing sends the mask back — the secret never passes
 * through the browser just because a form was rendered. Deleting is therefore an action of its
 * own with a name and not the side effect of an empty field.
 */
export default function MailServerSection() {
  const settings = useMailStore((s) => s.settings)
  const isLoading = useMailStore((s) => s.isLoadingSettings)
  const isSaving = useMailStore((s) => s.isSavingSettings)
  const error = useMailStore((s) => s.settingsError)
  const loadSettings = useMailStore((s) => s.loadSettings)
  const saveSettings = useMailStore((s) => s.saveSettings)
  const sendTestMail = useMailStore((s) => s.sendTestMail)

  const [form, setForm] = useState<FormState | null>(null)
  const [clearPassword, setClearPassword] = useState(false)
  const [testResult, setTestResult] = useState<MailSendResultResponse | null>(null)
  const [isTesting, setIsTesting] = useState(false)
  const [passwordError, setPasswordError] = useState<string | null>(null)

  useEffect(() => {
    void loadSettings()
  }, [loadSettings])

  // Adopt the loaded settings once; never overwrite what the administrator has started typing.
  const hydrated = useRef(false)
  useEffect(() => {
    if (hydrated.current || !settings) return
    hydrated.current = true
    setForm(formOf(settings))
  }, [settings])

  if (isLoading && !form) {
    return (
      <Box aria-busy="true">
        <span style={visuallyHidden}>Die E-Mail-Einstellungen werden geladen …</span>
        <Skeleton variant="rounded" height={120} sx={{ mb: 2.5 }} />
        <Skeleton variant="rounded" height={420} />
      </Box>
    )
  }
  if (!form || !settings) {
    return error ? <Alert severity="error">{error}</Alert> : null
  }

  // Local, non-null bindings so the handlers below need no assertions.
  const currentForm: FormState = form
  const currentSettings: MailSettingsResponse = settings

  const update = (patch: Partial<FormState>) => setForm({ ...currentForm, ...patch })
  const portNumber = parseSmtpPort(form.port)
  const isPortValid = !Number.isNaN(portNumber ?? 0)
  const canEnable = form.host.trim() !== '' && portNumber !== null && form.fromAddress.trim() !== ''
  const connectionChanged = smtpTargetChanged(
    {
      host: settings.host ?? null,
      port: settings.port ?? null,
      username: settings.username ?? null,
      encryption: settings.encryption,
    },
    { host: form.host, port: portNumber, username: form.username, encryption: form.encryption },
  )
  const passwordNeeded =
    settings.passwordSet && connectionChanged && !clearPassword && form.password === ''

  function passwordForRequest(): string {
    if (clearPassword) return ''
    if (currentForm.password !== '') return currentForm.password
    return currentSettings.passwordSet ? PASSWORD_MASK : ''
  }

  async function handleSave() {
    const request: MailSettingsUpdateRequest = {
      enabled: currentForm.enabled,
      host: currentForm.host.trim() || null,
      port: portNumber,
      username: currentForm.username.trim() || null,
      password: passwordForRequest(),
      encryption: currentForm.encryption,
      fromAddress: currentForm.fromAddress.trim() || null,
      fromName: currentForm.fromName.trim() || null,
    }
    setPasswordError(null)
    try {
      await saveSettings(request)
      setForm((current) => (current ? { ...current, password: '' } : current))
      setClearPassword(false)
      notify('Die E-Mail-Einstellungen wurden gespeichert.', 'success')
    } catch (err) {
      // the store holds the message for the alert above; a refusal of the password goes to its field
      setPasswordError(
        apiFieldErrors(err).find((fieldError) => fieldError.field === 'password')?.message ?? null,
      )
    }
  }

  async function handleTest() {
    setIsTesting(true)
    setTestResult(null)
    try {
      const result = await sendTestMail()
      setTestResult(result)
    } catch (err) {
      notify(
        err instanceof Error ? err.message : 'Der Testversand konnte nicht angestoßen werden.',
        'error',
      )
    } finally {
      setIsTesting(false)
    }
  }

  return (
    <Box>
      <MailStatusCard settings={settings} />

      {!settings.publicBaseUrlConfigured && <PublicBaseUrlMissingAlert sx={{ mb: 2.5 }} />}

      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}

      <PageSection title="Verbindung">
        <Stack spacing={2.5} component="section" aria-labelledby="mail-server-fields">
          <FormControlLabel
            control={
              <Switch
                checked={form.enabled}
                onChange={(e) => update({ enabled: e.target.checked })}
              />
            }
            label="Versand aktiv"
          />
          {form.enabled && !canEnable && <Alert severity="info">{SMTP_TEXTS.enableNeeds}</Alert>}

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label={SMTP_LABELS.host}
              value={form.host}
              onChange={(e) => update({ host: e.target.value })}
              helperText={SMTP_TEXTS.hostHint}
              sx={{ flexGrow: 1 }}
            />
            <TextField
              label={SMTP_LABELS.port}
              value={form.port}
              onChange={(e) => update({ port: e.target.value })}
              error={!isPortValid}
              helperText={isPortValid ? SMTP_TEXTS.portHint : SMTP_TEXTS.portInvalid}
              sx={{ width: { xs: '100%', sm: 160 } }}
            />
          </Stack>

          <TextField
            select
            label={SMTP_LABELS.encryption}
            value={form.encryption}
            onChange={(e) => update({ encryption: e.target.value as MailEncryption })}
            helperText={SMTP_ENCRYPTION_OPTIONS.find((o) => o.value === form.encryption)?.hint}
            fullWidth
          >
            {SMTP_ENCRYPTION_OPTIONS.map((option) => (
              <MenuItem key={option.value} value={option.value}>
                {option.label}
              </MenuItem>
            ))}
          </TextField>

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label={`${SMTP_LABELS.username} (optional)`}
              value={form.username}
              onChange={(e) => update({ username: e.target.value })}
              helperText={SMTP_TEXTS.usernameHint}
              sx={{ flexGrow: 1 }}
            />
            <TextField
              label={SMTP_LABELS.password}
              type="password"
              value={form.password}
              onChange={(e) => {
                setClearPassword(false)
                setPasswordError(null)
                update({ password: e.target.value })
              }}
              disabled={clearPassword}
              autoComplete="new-password"
              error={passwordError !== null || passwordNeeded}
              helperText={
                passwordError ??
                (clearPassword
                  ? 'Das gespeicherte Passwort wird beim Speichern gelöscht.'
                  : passwordNeeded
                    ? SMTP_TEXTS.passwordFollowsTarget
                    : settings.passwordSet
                      ? SMTP_TEXTS.passwordStored
                      : SMTP_TEXTS.passwordNone)
              }
              sx={{ flexGrow: 1 }}
            />
          </Stack>
          {settings.passwordSet && (
            <Box>
              <Button
                size="small"
                color={clearPassword ? 'primary' : 'error'}
                onClick={() => {
                  setClearPassword(!clearPassword)
                  update({ password: '' })
                }}
              >
                {clearPassword
                  ? 'Löschen des Passworts abbrechen'
                  : 'Gespeichertes Passwort löschen'}
              </Button>
            </Box>
          )}

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label={SMTP_LABELS.fromAddress}
              value={form.fromAddress}
              onChange={(e) => update({ fromAddress: e.target.value })}
              helperText={SMTP_TEXTS.fromAddressHint}
              sx={{ flexGrow: 1 }}
            />
            <TextField
              label={`${SMTP_LABELS.fromName} (optional)`}
              value={form.fromName}
              onChange={(e) => update({ fromName: e.target.value })}
              helperText={SMTP_TEXTS.fromNameHint}
              sx={{ flexGrow: 1 }}
            />
          </Stack>

          <Stack direction="row" spacing={2} sx={{ flexWrap: 'wrap', gap: 1 }}>
            <Button
              variant="contained"
              onClick={() => void handleSave()}
              disabled={isSaving || !isPortValid || (form.enabled && !canEnable)}
            >
              Speichern
            </Button>
            <Button
              variant="outlined"
              onClick={() => void handleTest()}
              disabled={isTesting || isSaving}
            >
              Testmail an mich senden
            </Button>
          </Stack>
          <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
            Die Testnachricht geht immer an die Adresse Ihres eigenen Kontos — eine andere lässt
            sich hier bewusst nicht eintragen.
          </Typography>

          {testResult && (
            <Alert severity={mailSendResultSeverity(testResult)}>
              {mailSendResultMessage(testResult)}
            </Alert>
          )}
        </Stack>
      </PageSection>
    </Box>
  )
}
