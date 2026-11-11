import { useEffect, useRef, useState } from 'react'
import { smtpDraftOf, type SmtpDraft, type StoredSmtp } from '@/shared/ui/setup/setupDrafts'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import FormControlLabel from '@mui/material/FormControlLabel'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import Typography from '@mui/material/Typography'
import type {
  MailSendResultResponse,
  MailSettingsResponse,
  MailSettingsUpdateRequest,
} from '@/shared/api/types'
import { useMailStore } from '../stores/mailStore'
import { notify } from '@/shared/stores/notificationStore'
import { apiFieldErrors } from '@/shared/api/apiErrorDetails'
import PageSection from '@/shared/ui/PageSection'
import PublicBaseUrlMissingAlert from '@/shared/ui/smtp/PublicBaseUrlMissingAlert'
import { useRecentLoginGate } from '@/shared/session/useRecentLoginGate'
import SmtpFields from '@/shared/ui/setup/SmtpFields'
import { SMTP_TEXTS, parseSmtpPort, smtpPortError } from '@/shared/ui/smtp/smtpSettings'
import MailStatusCard from './MailStatusCard'
import { mailSendResultMessage, mailSendResultSeverity } from './mailStatus'
import { MAIL_CONFIRM_PURPOSE } from './mailRecentLogin'
import { fontSize, formWidth } from '@/shared/theme/tokens'
import LoadError from '@/shared/ui/states/LoadError'
import LoadingState from '@/shared/ui/states/LoadingState'

/** Mirrors `MailSettingsService.PASSWORD_MASK`: sending it back means „unverändert". */
const PASSWORD_MASK = '***'

function storedOf(settings: MailSettingsResponse): StoredSmtp {
  return {
    host: settings.host ?? null,
    port: settings.port ?? null,
    username: settings.username ?? null,
    encryption: settings.encryption,
    fromAddress: settings.fromAddress,
    fromName: settings.fromName,
    passwordSet: settings.passwordSet,
  }
}

/**
 * The installation's SMTP form (ADR-0033 Entscheidung 10), with the fields of the assistant's
 * step „E-Mail“ (`SmtpFields`).
 *
 * The password is write-protected in both directions: the response carries only the mask, the
 * field reads „Gesetzt“, and whoever enters nothing sends the mask back — the secret never passes
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

  const [enabled, setEnabled] = useState(false)
  const [draft, setDraft] = useState<SmtpDraft | null>(null)
  // Remounts the fields after a save, so the password reads „Gesetzt“ again.
  const [savedCount, setSavedCount] = useState(0)
  const [testResult, setTestResult] = useState<MailSendResultResponse | null>(null)
  const [isTesting, setIsTesting] = useState(false)
  const [passwordError, setPasswordError] = useState<string | null>(null)
  const [refusal, setRefusal] = useState<string | null>(null)
  const gate = useRecentLoginGate(MAIL_CONFIRM_PURPOSE)

  useEffect(() => {
    void loadSettings()
  }, [loadSettings])

  // Adopt the loaded settings once; never overwrite what the administrator has started typing.
  const hydrated = useRef(false)
  useEffect(() => {
    if (hydrated.current || !settings) return
    hydrated.current = true
    setEnabled(settings.enabled)
    setDraft(smtpDraftOf(storedOf(settings)))
  }, [settings])

  if (isLoading && !draft) {
    return <LoadingState shape="detail" count={8} />
  }
  if (!draft || !settings) {
    return error ? (
      <LoadError
        message="Die E-Mail-Einstellungen konnten nicht geladen werden."
        detail={error}
        onRetry={() => void loadSettings()}
      />
    ) : null
  }

  // Local, non-null bindings so the handlers below need no assertions.
  const currentDraft: SmtpDraft = draft
  const currentSettings: MailSettingsResponse = settings

  const portNumber = parseSmtpPort(draft.port)
  const portError = smtpPortError(draft.port)
  const canEnable =
    draft.host.trim() !== '' && portNumber !== null && draft.fromAddress.trim() !== ''

  function passwordForRequest(): string {
    if (currentDraft.removePassword) return ''
    if (currentDraft.password !== '') return currentDraft.password
    return currentSettings.passwordSet ? PASSWORD_MASK : ''
  }

  async function handleSave(afterConfirmation = false) {
    const request: MailSettingsUpdateRequest = {
      enabled,
      host: currentDraft.host.trim() || null,
      port: portNumber,
      username: currentDraft.username.trim() || null,
      password: passwordForRequest(),
      encryption: currentDraft.encryption,
      fromAddress: currentDraft.fromAddress.trim() || null,
      fromName: currentDraft.fromName.trim() || null,
    }
    setPasswordError(null)
    setRefusal(null)
    try {
      await saveSettings(request)
      gate.settled()
      setDraft((current) =>
        current ? { ...current, password: '', removePassword: false } : current,
      )
      setSavedCount((count) => count + 1)
      notify('Die E-Mail-Einstellungen wurden gespeichert.', 'success')
    } catch (err) {
      const refused = gate.refusal(err, () => void handleSave(true), afterConfirmation)
      if (refused !== null) {
        if (refused) setRefusal(refused)
        return
      }
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

      {(refusal ?? error) && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {refusal ?? error}
        </Alert>
      )}

      <PageSection title="Verbindung">
        <Stack
          spacing={2.5}
          component="section"
          aria-labelledby="mail-server-fields"
          sx={{ maxWidth: formWidth.column }}
        >
          <FormControlLabel
            control={<Switch checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />}
            label="Versand aktiv"
          />
          {enabled && !canEnable && <Alert severity="info">{SMTP_TEXTS.enableNeeds}</Alert>}

          <SmtpFields
            key={savedCount}
            idPrefix="mail-server"
            value={draft}
            onChange={(patch) => {
              if ('password' in patch || 'removePassword' in patch) setPasswordError(null)
              setDraft({ ...currentDraft, ...patch })
            }}
            errors={{ port: portError, password: passwordError ?? undefined }}
            stored={storedOf(settings)}
          />

          <Stack direction="row" spacing={2} sx={{ flexWrap: 'wrap', gap: 1 }}>
            <Button
              variant="contained"
              onClick={() => void handleSave()}
              disabled={isSaving || portError !== undefined || (enabled && !canEnable)}
            >
              Speichern
            </Button>
            <Button
              variant="outlined"
              onClick={() => void handleTest()}
              disabled={isTesting || isSaving}
            >
              Testnachricht an mich senden
            </Button>
          </Stack>
          <Typography sx={{ fontSize: fontSize.meta, color: 'text.secondary' }}>
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
      {gate.dialog}
    </Box>
  )
}
