import { useEffect, useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import AlertTitle from '@mui/material/AlertTitle'
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
} from '../../../types/api'
import { useMailStore } from '../../../stores/mailStore'
import { notify } from '../../../stores/notificationStore'
import PageSection from '../../PageSection'
import MailStatusCard from './MailStatusCard'
import { mailSendResultMessage, mailSendResultSeverity } from './mailStatus'

/** Mirrors `MailSettingsService.PASSWORD_MASK`: sending it back means „unverändert". */
const PASSWORD_MASK = '***'

const encryptionOptions: Array<{ value: MailEncryption; label: string; hint: string }> = [
  {
    value: 'NONE',
    label: 'Keine',
    hint: 'Unverschlüsseltes SMTP — nur im eigenen, sicheren Netz.',
  },
  {
    value: 'STARTTLS',
    label: 'STARTTLS',
    hint: 'Verschlüsselung wird nach dem Verbindungsaufbau verlangt, nicht still übersprungen.',
  },
  { value: 'SSL', label: 'SSL/TLS', hint: 'Verschlüsselt ab dem ersten Byte (meist Port 465).' },
]

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
 * Das SMTP-Formular der Installation (#1542, ADR-0033 Entscheidung 10).
 *
 * Das Passwort ist in beide Richtungen schreibgeschützt: Die Antwort trägt nur die Maske, das
 * Feld startet leer, und wer nichts eingibt, schickt die Maske zurück — das Geheimnis läuft nie
 * durch den Browser, bloß weil ein Formular gerendert wurde. Löschen ist deshalb eine eigene,
 * benannte Handlung und nicht die Nebenwirkung eines leeren Feldes.
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
  const portNumber = form.port.trim() === '' ? null : Number(form.port)
  const isPortValid =
    portNumber === null || (Number.isInteger(portNumber) && portNumber > 0 && portNumber <= 65535)
  const canEnable = form.host.trim() !== '' && portNumber !== null && form.fromAddress.trim() !== ''

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
    try {
      await saveSettings(request)
      setForm((current) => (current ? { ...current, password: '' } : current))
      setClearPassword(false)
      notify('Die E-Mail-Einstellungen wurden gespeichert.', 'success')
    } catch {
      // the store holds the message; the alert above renders it
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

      {!settings.publicBaseUrlConfigured && (
        <Alert severity="warning" sx={{ mb: 2.5 }}>
          <AlertTitle>Öffentliche Adresse fehlt</AlertTitle>
          OPAA_PUBLIC_BASE_URL ist nicht gesetzt. Einladungen, Rücksetzlinks und Bestätigungen
          enthalten dann keinen brauchbaren Link — die betroffenen Wege bleiben abgeschaltet. Tragen
          Sie die Adresse, unter der diese Installation erreichbar ist, in der Umgebung der
          Bereitstellung ein; im Browser ist sie bewusst nicht änderbar.
        </Alert>
      )}

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
          {form.enabled && !canEnable && (
            <Alert severity="info">
              Zum Einschalten werden Server, Port und Absenderadresse gebraucht.
            </Alert>
          )}

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="Server"
              value={form.host}
              onChange={(e) => update({ host: e.target.value })}
              helperText="Rechnername oder Adresse des Mailservers, zum Beispiel smtp.intern.example."
              sx={{ flexGrow: 1 }}
            />
            <TextField
              label="Port"
              value={form.port}
              onChange={(e) => update({ port: e.target.value })}
              error={!isPortValid}
              helperText={
                isPortValid
                  ? 'Üblich: 587 (STARTTLS), 465 (SSL/TLS), 25.'
                  : 'Bitte eine Portnummer zwischen 1 und 65535 angeben.'
              }
              sx={{ width: { xs: '100%', sm: 160 } }}
            />
          </Stack>

          <TextField
            select
            label="Verschlüsselung"
            value={form.encryption}
            onChange={(e) => update({ encryption: e.target.value as MailEncryption })}
            helperText={encryptionOptions.find((o) => o.value === form.encryption)?.hint}
            fullWidth
          >
            {encryptionOptions.map((option) => (
              <MenuItem key={option.value} value={option.value}>
                {option.label}
              </MenuItem>
            ))}
          </TextField>

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="Benutzername (optional)"
              value={form.username}
              onChange={(e) => update({ username: e.target.value })}
              helperText="Leer lassen, wenn der Server ohne Anmeldung annimmt."
              sx={{ flexGrow: 1 }}
            />
            <TextField
              label="Passwort"
              type="password"
              value={form.password}
              onChange={(e) => {
                setClearPassword(false)
                update({ password: e.target.value })
              }}
              disabled={clearPassword}
              autoComplete="new-password"
              helperText={
                clearPassword
                  ? 'Das gespeicherte Passwort wird beim Speichern gelöscht.'
                  : settings.passwordSet
                    ? 'Ein Passwort ist hinterlegt. Leer lassen lässt es unverändert.'
                    : 'Es ist kein Passwort hinterlegt.'
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
              label="Absenderadresse"
              value={form.fromAddress}
              onChange={(e) => update({ fromAddress: e.target.value })}
              helperText="Die Adresse, unter der OPAA versendet."
              sx={{ flexGrow: 1 }}
            />
            <TextField
              label="Absendername (optional)"
              value={form.fromName}
              onChange={(e) => update({ fromName: e.target.value })}
              helperText="Erscheint im Postfach vor der Adresse."
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
