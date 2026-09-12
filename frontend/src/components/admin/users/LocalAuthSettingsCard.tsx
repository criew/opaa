import { useEffect, useState } from 'react'
import { Link as RouterLink } from 'react-router'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import FormControlLabel from '@mui/material/FormControlLabel'
import Link from '@mui/material/Link'
import Paper from '@mui/material/Paper'
import Skeleton from '@mui/material/Skeleton'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import type { LocalAuthSettingsResponse, LocalAuthSettingsUpdateRequest } from '../../../types/api'
import { useMailStore } from '../../../stores/mailStore'
import { notify } from '../../../stores/notificationStore'
import { useUserAdminStore } from '../../../stores/userAdminStore'
import { radius } from '../../../theme/tokens'
import FieldLabel from '../../wizard/FieldLabel'
import SectionHead from '../../SectionHead'
import { mailStatusOf } from '../mail/mailStatus'
import { localUserErrorMessage } from './localUserLabels'

export const DISABLE_LOCAL_ACCOUNTS_CONSEQUENCE =
  'Reguläre lokale Konten können sich ab sofort nicht mehr anmelden, und ihre laufenden Sitzungen ' +
  'enden sofort. Lokale Systemverwalter bleiben angemeldet und können sich weiter über ' +
  '/login/system anmelden – das ist der Weg zurück in eine Installation ohne funktionierenden ' +
  'Identitätsanbieter. Die Konten und ihre Rechte bleiben erhalten.'

export const SELF_REGISTRATION_CONSEQUENCE =
  'Die Registrierung ist danach ein öffentlich erreichbares Formular. Es gilt nur für die unten ' +
  'eingetragenen Adress-Domänen, die Rate-Limits der Anmeldung greifen, jedes Konto wird mit dem ' +
  'Pflicht-Ablaufdatum und dem Anlagegrund „Selbstregistrierung" erzeugt. Das Einschalten ist ein ' +
  'Protokollereignis und nach der Dienstvereinbarung mitbestimmungspflichtig.'

export const PUBLIC_BASE_URL_HINT =
  'Nicht einschaltbar, solange die Umgebungsvariable OPAA_PUBLIC_BASE_URL nicht gesetzt ist – ' +
  'ohne sie gibt es keinen Link, den jemand sehen könnte.'

const DOMAIN_REQUIRED_HINT =
  'Tragen Sie zuerst mindestens eine Adress-Domäne ein – ohne Domänenliste kann sich niemand registrieren.'

interface RulesDraft {
  domains: string
  passwordMinLength: string
  defaultExpiryDays: string
  inactiveDays: string
  invitationTokenTtlHours: string
  resetTokenTtlMinutes: string
}

function draftOf(settings: LocalAuthSettingsResponse): RulesDraft {
  return {
    domains: settings.selfRegistrationAllowedDomains.join(', '),
    passwordMinLength: String(settings.passwordMinLength),
    defaultExpiryDays: String(settings.defaultExpiryDays),
    inactiveDays: String(settings.inactiveDays),
    invitationTokenTtlHours: String(settings.invitationTokenTtlHours),
    resetTokenTtlMinutes: String(settings.resetTokenTtlMinutes),
  }
}

function parseDomains(value: string): string[] {
  return value
    .split(/[,\s;]+/)
    .map((entry) => entry.trim().toLowerCase())
    .filter((entry) => entry !== '')
}

/** The complete PUT body; the endpoint replaces the settings, so every value travels along. */
function requestOf(
  settings: LocalAuthSettingsResponse,
  draft: RulesDraft,
  overrides: Partial<LocalAuthSettingsUpdateRequest> = {},
): LocalAuthSettingsUpdateRequest {
  return {
    enabled: settings.enabled,
    selfRegistrationEnabled: settings.selfRegistrationEnabled,
    selfRegistrationAllowedDomains: parseDomains(draft.domains),
    passwordResetEnabled: settings.passwordResetEnabled,
    passwordMinLength: Number(draft.passwordMinLength),
    invitationTokenTtlHours: Number(draft.invitationTokenTtlHours),
    resetTokenTtlMinutes: Number(draft.resetTokenTtlMinutes),
    defaultExpiryDays: Number(draft.defaultExpiryDays),
    inactiveDays: Number(draft.inactiveDays),
    ...overrides,
  }
}

function SmtpStatusLine() {
  const settings = useMailStore((s) => s.settings)
  const loadSettings = useMailStore((s) => s.loadSettings)

  useEffect(() => {
    if (!settings) void loadSettings()
  }, [settings, loadSettings])

  const status = settings ? mailStatusOf(settings) : null
  const usable = status?.kind === 'SUCCESS' || status?.kind === 'UNTESTED'

  return (
    <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
      <Box
        aria-hidden="true"
        sx={{
          width: 8,
          height: 8,
          borderRadius: '50%',
          flex: 'none',
          bgcolor: !status ? 'text.disabled' : usable ? 'success.main' : 'warning.main',
        }}
      />
      <Typography component="span" sx={{ fontSize: 12.5 }}>
        E-Mail-Versand: {status ? status.headline : 'Status wird geladen …'}
      </Typography>
      <Typography component="span" sx={{ fontSize: 12.5, color: 'text.secondary' }}>
        ·{' '}
        <Link component={RouterLink} to="/admin/mail/server">
          E-Mail-Einstellungen
        </Link>
      </Typography>
      {status && !usable && (
        <Typography component="span" sx={{ fontSize: 12.5, color: 'text.secondary' }}>
          · Einladungen und Rücksetzlinks werden dann zur Übergabe angezeigt.
        </Typography>
      )}
    </Stack>
  )
}

interface LinkFlowSwitchProps {
  label: string
  checked: boolean
  disabled: boolean
  /** Set while the flow cannot be switched on at all - the switch is then locked with this hint. */
  lockedHint: string | null
  /** Set while the flow is switched on but has no effect - the switch says so instead of lying. */
  ineffectiveHint: string | null
  onChange: (next: boolean) => void
}

/**
 * Einer der beiden Schalter, die einen Link brauchen. Ohne `OPAA_PUBLIC_BASE_URL` ist er gesperrt
 * und sagt warum (ADR-0033, Entscheidung 10) - der Tooltip erscheint nur dann, damit er nicht als
 * leeres `aria-label` am Bedienelement hängt.
 */
function LinkFlowSwitch({
  label,
  checked,
  disabled,
  lockedHint,
  ineffectiveHint,
  onChange,
}: LinkFlowSwitchProps) {
  const control = (
    <FormControlLabel
      control={
        <Switch
          checked={checked}
          disabled={disabled || lockedHint !== null}
          onChange={(e) => onChange(e.target.checked)}
        />
      }
      label={label}
    />
  )
  return (
    <Box>
      {lockedHint === null ? (
        control
      ) : (
        <Tooltip title={lockedHint}>
          <span>{control}</span>
        </Tooltip>
      )}
      {ineffectiveHint !== null && (
        <Typography sx={{ fontSize: 12, color: 'text.secondary', ml: 6, mt: -0.5 }}>
          {ineffectiveHint}
        </Typography>
      )}
    </Box>
  )
}

/**
 * Warum ein eingeschalteter Fluss gerade trotzdem nicht erreichbar ist (Review-Runde 1, MEDIUM 6).
 * Ein Schalter, der „ein" zeigt, obwohl das Backend den Fluss als abgeschaltet meldet, wäre eine
 * falsche Auskunft; den gespeicherten Wert ändert der Satz nicht.
 */
function ineffectiveReason(settings: LocalAuthSettingsResponse, domains?: string[]): string | null {
  if (!settings.publicBaseUrlConfigured) {
    return 'Derzeit nicht erreichbar: ohne OPAA_PUBLIC_BASE_URL meldet das Backend den Fluss als abgeschaltet.'
  }
  if (!settings.enabled) {
    return 'Derzeit nicht erreichbar, weil die lokale Anmeldung abgeschaltet ist.'
  }
  if (domains && domains.length === 0) {
    return 'Derzeit nicht erreichbar, weil keine Adress-Domäne eingetragen ist.'
  }
  return null
}

/**
 * Die Kopfkarte der Benutzerverwaltung (#1541, ADR-0033 Entscheidungen 4 und 10): die drei
 * Schalter der lokalen Anmeldung mit ihren Konsequenzen und Vorbedingungen, die Regeln und
 * Fristen, und der Zustand des Mailversands – weil Einladung und Rücksetzung ohne ihn nur als
 * Link-Übergabe funktionieren.
 *
 * Die Schalter wirken sofort (ein PUT je Umschalten), die Regeln darunter über „Speichern": ein
 * halb getippter Zahlenwert darf nicht mit einem Klick auf einen Schalter gespeichert werden.
 */
export default function LocalAuthSettingsCard() {
  const settings = useUserAdminStore((s) => s.settings)
  const isLoading = useUserAdminStore((s) => s.isLoadingSettings)
  const isSaving = useUserAdminStore((s) => s.isSavingSettings)
  const loadError = useUserAdminStore((s) => s.settingsError)
  const loadSettings = useUserAdminStore((s) => s.loadSettings)
  const saveSettings = useUserAdminStore((s) => s.saveSettings)

  /**
   * `null` heißt „folge dem Server": Die Regeln werden aus der Antwort abgeleitet, bis jemand ein
   * Feld anfasst. Abgeleitet beim Rendern statt in einem Effekt gespiegelt – und nach jedem
   * Speichern wieder auf `null`, damit das Formular zeigt, was tatsächlich gespeichert wurde.
   */
  const [edited, setEdited] = useState<RulesDraft | null>(null)
  const [error, setError] = useState<string | null>(null)
  const draft = edited ?? (settings ? draftOf(settings) : null)

  useEffect(() => {
    void loadSettings()
  }, [loadSettings])

  if (isLoading && !settings) {
    return <Skeleton variant="rounded" height={260} sx={{ mb: 3 }} />
  }
  if (!settings || !draft) {
    return (
      <Alert severity="error" sx={{ mb: 3 }}>
        {loadError ?? 'Die Einstellungen der lokalen Anmeldung konnten nicht geladen werden.'}
      </Alert>
    )
  }

  const domains = parseDomains(draft.domains)

  /**
   * `discardDraft` nur dort, wo der Entwurf tatsächlich gespeichert wurde (Nachprüfung N4): Ein
   * Schalterklick verwarf sonst still, was jemand gerade in die Regeln getippt hatte.
   */
  async function persist(
    request: LocalAuthSettingsUpdateRequest,
    success: (saved: LocalAuthSettingsResponse) => string,
    discardDraft = false,
  ) {
    setError(null)
    try {
      const saved = await saveSettings(request)
      if (discardDraft) setEdited(null)
      notify(success(saved), 'success')
    } catch (err) {
      setError(localUserErrorMessage(err, 'Die Einstellung konnte nicht gespeichert werden.'))
    }
  }

  /**
   * Ein Schalterklick speichert den **Serverstand** plus seine eigene Änderung – nie den Entwurf
   * der Regeln darunter (Review-Runde 1, HIGH 1): Ein halb getippter Zahlenwert würde sonst
   * mitgespeichert, und ein leeres Feld ließe den Schalter als `NaN` still an der Validierung
   * scheitern. `overrides` darf die Domänenliste mitführen – sie ist die Vorbedingung des
   * Selbstregistrierungs-Schalters und gehört damit zu genau dieser Handlung.
   */
  function saveSwitch(
    overrides: Partial<LocalAuthSettingsUpdateRequest>,
    success: (saved: LocalAuthSettingsResponse) => string,
  ) {
    void persist(requestOf(settings!, draftOf(settings!), overrides), success)
  }

  function toggleEnabled(next: boolean) {
    if (
      !next &&
      !window.confirm(`Lokale Anmeldung abschalten?\n\n${DISABLE_LOCAL_ACCOUNTS_CONSEQUENCE}`)
    ) {
      return
    }
    saveSwitch({ enabled: next }, (saved) =>
      next
        ? 'Die lokale Anmeldung ist eingeschaltet.'
        : `Die lokale Anmeldung ist abgeschaltet. ${
            saved.revokedSessions === 1
              ? '1 lokales Konto hat seine Sitzung verloren.'
              : `${saved.revokedSessions ?? 0} lokale Konten haben ihre Sitzung verloren.`
          }`,
    )
  }

  function toggleSelfRegistration(next: boolean) {
    if (next && domains.length === 0) {
      setError(DOMAIN_REQUIRED_HINT)
      return
    }
    if (
      next &&
      !window.confirm(`Selbstregistrierung einschalten?\n\n${SELF_REGISTRATION_CONSEQUENCE}`)
    ) {
      return
    }
    saveSwitch(
      // Die Domänenliste reist **nur in Einschaltrichtung** mit (Nachprüfung N1): Beim Ausschalten
      // wäre sie kein Teil der Handlung, und ein halb getippter Eintrag würde mitgespeichert.
      next
        ? { selfRegistrationEnabled: true, selfRegistrationAllowedDomains: domains }
        : { selfRegistrationEnabled: false },
      () =>
        next
          ? 'Die Selbstregistrierung ist eingeschaltet.'
          : 'Die Selbstregistrierung ist abgeschaltet.',
    )
  }

  function togglePasswordReset(next: boolean) {
    saveSwitch({ passwordResetEnabled: next }, () =>
      next ? '„Passwort vergessen" ist eingeschaltet.' : '„Passwort vergessen" ist abgeschaltet.',
    )
  }

  function saveRules() {
    if (settings!.selfRegistrationEnabled && domains.length === 0) {
      setError(DOMAIN_REQUIRED_HINT)
      return
    }
    void persist(
      requestOf(settings!, draft!),
      () => 'Die Regeln der lokalen Anmeldung wurden gespeichert.',
      true,
    )
  }

  const baseUrlMissing = !settings.publicBaseUrlConfigured

  return (
    <Paper
      variant="outlined"
      component="section"
      aria-labelledby="local-auth-settings-title"
      sx={{ p: { xs: 2, md: 2.5 }, borderRadius: `${radius.md}px`, mb: 3 }}
    >
      <SectionHead id="local-auth-settings-title">Lokale Anmeldung</SectionHead>

      {baseUrlMissing && (
        <Alert severity="info" sx={{ mb: 2 }}>
          {PUBLIC_BASE_URL_HINT}
        </Alert>
      )}
      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, minmax(0, 1fr))' },
          columnGap: 3,
          rowGap: 0.5,
          mb: 1.5,
        }}
      >
        <FormControlLabel
          control={
            <Switch
              checked={settings.enabled}
              disabled={isSaving}
              onChange={(e) => toggleEnabled(e.target.checked)}
            />
          }
          label="Lokale Anmeldung aktiv"
        />
        <LinkFlowSwitch
          label="Selbstregistrierung"
          checked={settings.selfRegistrationEnabled}
          disabled={isSaving}
          lockedHint={
            baseUrlMissing && !settings.selfRegistrationEnabled ? PUBLIC_BASE_URL_HINT : null
          }
          ineffectiveHint={
            settings.selfRegistrationEnabled
              ? ineffectiveReason(settings, parseDomains(draftOf(settings).domains))
              : null
          }
          onChange={toggleSelfRegistration}
        />
        <LinkFlowSwitch
          label="Passwort vergessen"
          checked={settings.passwordResetEnabled}
          disabled={isSaving}
          lockedHint={
            baseUrlMissing && !settings.passwordResetEnabled ? PUBLIC_BASE_URL_HINT : null
          }
          ineffectiveHint={settings.passwordResetEnabled ? ineffectiveReason(settings) : null}
          onChange={togglePasswordReset}
        />
      </Box>

      <SmtpStatusLine />

      <Box sx={{ mt: 2.5, pt: 2.5, borderTop: 1, borderColor: 'divider' }}>
        <Typography component="h3" sx={{ fontSize: 13.5, fontWeight: 600, mb: 1.5 }}>
          Regeln und Fristen
        </Typography>
        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, minmax(0, 1fr))' },
            columnGap: 3,
            rowGap: 2,
          }}
        >
          <Box sx={{ gridColumn: { sm: 'span 2' } }}>
            <FieldLabel htmlFor="local-auth-domains">
              Adress-Domänen der Selbstregistrierung
            </FieldLabel>
            <TextField
              id="local-auth-domains"
              fullWidth
              size="small"
              value={draft.domains}
              placeholder="stadt.example, amt.example"
              onChange={(e) => setEdited({ ...draft, domains: e.target.value })}
              helperText="Komma- oder leerzeichengetrennt. Pflicht für die Selbstregistrierung; leer bedeutet, dass sich niemand registrieren kann."
            />
          </Box>
          {(
            [
              ['passwordMinLength', 'Mindestlänge des Passworts', '8 bis 64 Zeichen'],
              ['defaultExpiryDays', 'Vorbelegtes Ablaufdatum (Tage)', 'Vorschlag beim Anlegen'],
              ['inactiveDays', 'Sperre nach Inaktivität (Tage)', 'mindestens 30'],
              ['invitationTokenTtlHours', 'Einladungslink gültig (Stunden)', '1 bis 720'],
              ['resetTokenTtlMinutes', 'Rücksetzlink gültig (Minuten)', '1 bis 1440'],
            ] as Array<[keyof RulesDraft, string, string]>
          ).map(([field, label, help]) => (
            <Box key={field}>
              <FieldLabel htmlFor={`local-auth-${field}`}>{label}</FieldLabel>
              <TextField
                id={`local-auth-${field}`}
                fullWidth
                size="small"
                type="number"
                value={draft[field]}
                onChange={(e) => setEdited({ ...draft, [field]: e.target.value })}
                helperText={help}
              />
            </Box>
          ))}
        </Box>
        <Stack direction="row" spacing={1} sx={{ mt: 2 }}>
          <Button variant="outlined" size="small" onClick={saveRules} disabled={isSaving}>
            Regeln speichern
          </Button>
          <Button size="small" onClick={() => setEdited(null)} disabled={isSaving}>
            Verwerfen
          </Button>
        </Stack>
      </Box>
    </Paper>
  )
}
