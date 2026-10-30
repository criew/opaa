import { useEffect, useRef, useState } from 'react'
import { Link as RouterLink } from 'react-router'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import FormControlLabel from '@mui/material/FormControlLabel'
import Link from '@mui/material/Link'
import Skeleton from '@mui/material/Skeleton'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import type { LocalAuthSettingsResponse, LocalAuthSettingsUpdateRequest } from '@/shared/api/types'
import { confirmAction } from '@/shared/stores/confirmStore'
import { useMailStore } from '@/features/mail'
import { notify } from '@/shared/stores/notificationStore'
import { useUserAdminStore } from '../stores/userAdminStore'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'
import PageSection from '@/shared/ui/PageSection'
import { mailStatusOf } from '@/features/mail'
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
  /** `null` while the factory default applies. */
  lockoutMaxAttempts: string | null
  /** `null` while the factory default applies. */
  lockoutDurationMinutes: string | null
}

function storedText(value: number | null | undefined): string | null {
  return value === null || value === undefined ? null : String(value)
}

function draftOf(settings: LocalAuthSettingsResponse): RulesDraft {
  return {
    domains: settings.selfRegistrationAllowedDomains.join(', '),
    passwordMinLength: String(settings.passwordMinLength),
    defaultExpiryDays: String(settings.defaultExpiryDays),
    inactiveDays: String(settings.inactiveDays),
    invitationTokenTtlHours: String(settings.invitationTokenTtlHours),
    resetTokenTtlMinutes: String(settings.resetTokenTtlMinutes),
    lockoutMaxAttempts: storedText(settings.storedLockoutMaxAttempts),
    lockoutDurationMinutes: storedText(settings.storedLockoutDurationMinutes),
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
    lockoutMaxAttempts: draft.lockoutMaxAttempts === null ? null : Number(draft.lockoutMaxAttempts),
    lockoutDurationMinutes:
      draft.lockoutDurationMinutes === null ? null : Number(draft.lockoutDurationMinutes),
    ...overrides,
  }
}

interface LockoutFieldProps {
  id: string
  label: string
  /** The draft; `null` while the factory default applies. */
  value: string | null
  stored: number | null | undefined
  inForce: number
  factory: number
  /** The factory default as it applies within the limits of the operation. */
  factoryInForce: number
  range: string
  resetLabel: string
  disabled: boolean
  onChange: (value: string | null) => void
}

/** Whether the operation left the limits at the factory default: nothing looser can be set. */
function limitsAreFactoryOnly(settings: LocalAuthSettingsResponse): boolean {
  return (
    settings.lockoutMaxAttemptsLimit <= settings.defaultLockoutMaxAttempts ||
    settings.minLockoutDurationMinutes >= settings.defaultLockoutDurationMinutes
  )
}

/** Why the field shows its value: factory default, own value, or a stored value the limit bounds. */
function lockoutHint({ value, stored, inForce, factory, range }: LockoutFieldProps): string {
  if (value === null) return `Werkseinstellung; ${range} (Grenze des Betriebs)`
  if (stored != null && Number(value) === stored && stored !== inForce) {
    return `Gespeichert, wirkt aber nur als ${inForce} (Grenze des Betriebs)`
  }
  return `Eigener Wert (Werkseinstellung: ${factory}); ${range} (Grenze des Betriebs)`
}

/**
 * One value of the lockout after failed sign-ins (ADR-0060, Entscheidung 17): the operation sets
 * its limits, the administration a value within them or the factory default.
 */
function LockoutField(props: LockoutFieldProps) {
  const { id, label, value, factoryInForce, resetLabel, disabled, onChange } = props
  const inputRef = useRef<HTMLInputElement>(null)
  // The reset button disappears with the own value; the focus goes to the field it reset.
  function resetToFactory() {
    onChange(null)
    inputRef.current?.focus()
  }
  return (
    <Box>
      <FieldLabel htmlFor={id}>{label}</FieldLabel>
      <TextField
        id={id}
        inputRef={inputRef}
        fullWidth
        size="small"
        type="number"
        value={value ?? String(factoryInForce)}
        onChange={(e) => onChange(e.target.value)}
        helperText={lockoutHint(props)}
      />
      {value !== null && (
        <Button
          size="small"
          sx={{ mt: 0.5 }}
          aria-label={resetLabel}
          disabled={disabled}
          onClick={resetToFactory}
        >
          Werkseinstellung
        </Button>
      )}
    </Box>
  )
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
 * One of the two switches that need a link. Without `OPAA_PUBLIC_BASE_URL` it is disabled
 * and says why (ADR-0033, Entscheidung 10) - the tooltip appears only then, so that it does not
 * hang on the control as an empty `aria-label`.
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
 * Why a switched-on flow is still not reachable right now.
 * A switch that shows „ein" although the backend reports the flow as switched off would be
 * false information; the sentence does not change the stored value.
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
 * The head card of the user management (ADR-0033 Entscheidungen 4 and 10): the three
 * switches of the local login with their consequences and preconditions, the rules and
 * periods, and the state of mail sending – because invitation and reset work only as
 * link handover without it.
 *
 * The switches take effect at once (one PUT per toggle), the rules below via „Speichern": a
 * half-typed number must not be saved with a click on a switch.
 */
export default function LocalAuthSettingsCard() {
  const settings = useUserAdminStore((s) => s.settings)
  const isLoading = useUserAdminStore((s) => s.isLoadingSettings)
  const isSaving = useUserAdminStore((s) => s.isSavingSettings)
  const loadError = useUserAdminStore((s) => s.settingsError)
  const loadSettings = useUserAdminStore((s) => s.loadSettings)
  const saveSettings = useUserAdminStore((s) => s.saveSettings)

  /**
   * `null` means „follow the server": the rules are derived from the response until someone
   * touches a field. Derived while rendering instead of mirrored in an effect – and back to
   * `null` after every save, so that the form shows what was actually saved.
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
   * `discardDraft` only where the draft was actually saved (re-check N4): a
   * switch click would otherwise silently discard what someone had just typed into the rules.
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
   * A switch click saves the **server state** plus its own change – never the draft
   * of the rules below: a half-typed number would otherwise be
   * saved along, and an empty field would let the switch silently fail validation as `NaN`.
   * `overrides` may carry the domain list – it is the precondition of the
   * self-registration switch and thus belongs to exactly this action.
   */
  function saveSwitch(
    overrides: Partial<LocalAuthSettingsUpdateRequest>,
    success: (saved: LocalAuthSettingsResponse) => string,
  ) {
    void persist(requestOf(settings!, draftOf(settings!), overrides), success)
  }

  async function toggleEnabled(next: boolean) {
    if (
      !next &&
      !(await confirmAction({
        question: 'Lokale Anmeldung abschalten?',
        consequence: DISABLE_LOCAL_ACCOUNTS_CONSEQUENCE,
        confirmLabel: 'Abschalten',
        tone: 'caution',
      }))
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

  async function toggleSelfRegistration(next: boolean) {
    if (next && domains.length === 0) {
      setError(DOMAIN_REQUIRED_HINT)
      return
    }
    if (
      next &&
      !(await confirmAction({
        question: 'Selbstregistrierung einschalten?',
        consequence: SELF_REGISTRATION_CONSEQUENCE,
        confirmLabel: 'Einschalten',
        tone: 'caution',
      }))
    ) {
      return
    }
    saveSwitch(
      // The domain list travels along **only when switching on** (re-check N1): when switching off
      // it would be no part of the action, and a half-typed entry would be saved along.
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
    <PageSection title="Lokale Anmeldung">
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
              onChange={(e) => void toggleEnabled(e.target.checked)}
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
          onChange={(next) => void toggleSelfRegistration(next)}
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
        <Typography component="h3" sx={{ fontSize: 13.5, fontWeight: 600, mt: 3, mb: 0.5 }}>
          Sperre nach Fehlversuchen
        </Typography>
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mb: 1.5 }}>
          Gilt ab dem nächsten Fehlversuch; eine laufende Sperre behält ihr Ende. Die Grenzen legt
          der Betrieb fest, abschalten lässt sich die Sperre nicht.
          {limitsAreFactoryOnly(settings) && (
            <>
              {' '}
              Ab Werk lässt der Betrieb nur strengere Werte zu als die Werkseinstellung; lockern
              geht erst, wenn er OPAA_AUTH_LOCAL_LOCKOUT_MAX_ATTEMPTS oder
              OPAA_AUTH_LOCAL_LOCKOUT_MIN_DURATION weiter setzt.
            </>
          )}
        </Typography>
        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, minmax(0, 1fr))' },
            columnGap: 3,
            rowGap: 2,
          }}
        >
          <LockoutField
            id="local-auth-lockoutMaxAttempts"
            label="Fehlversuche bis zur Sperre"
            value={draft.lockoutMaxAttempts}
            stored={settings.storedLockoutMaxAttempts}
            inForce={settings.lockoutMaxAttempts}
            factory={settings.defaultLockoutMaxAttempts}
            factoryInForce={Math.min(
              settings.defaultLockoutMaxAttempts,
              settings.lockoutMaxAttemptsLimit,
            )}
            range={`1 bis ${settings.lockoutMaxAttemptsLimit}`}
            resetLabel="Fehlversuche auf die Werkseinstellung setzen"
            disabled={isSaving}
            onChange={(value) => setEdited({ ...draft, lockoutMaxAttempts: value })}
          />
          <LockoutField
            id="local-auth-lockoutDurationMinutes"
            label="Dauer der Sperre (Minuten)"
            value={draft.lockoutDurationMinutes}
            stored={settings.storedLockoutDurationMinutes}
            inForce={settings.lockoutDurationMinutes}
            factory={settings.defaultLockoutDurationMinutes}
            factoryInForce={Math.min(
              Math.max(settings.defaultLockoutDurationMinutes, settings.minLockoutDurationMinutes),
              settings.maxLockoutDurationMinutes,
            )}
            range={`${settings.minLockoutDurationMinutes} bis ${settings.maxLockoutDurationMinutes} Minuten`}
            resetLabel="Dauer der Sperre auf die Werkseinstellung setzen"
            disabled={isSaving}
            onChange={(value) => setEdited({ ...draft, lockoutDurationMinutes: value })}
          />
        </Box>
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 1.5 }}>
          Sehr strenge Werte sperren auch das Notanker-Konto der Systemverwaltung entsprechend
          lange. Schützen lässt es sich mit OPAA_LOCAL_ADMIN_ALLOWED_CIDRS; entsperren kann es eine
          andere Systemverwaltung oder der Betrieb mit OPAA_LOCAL_ADMIN_RESET=force.
        </Typography>
        <Stack direction="row" spacing={1} sx={{ mt: 2 }}>
          <Button variant="outlined" size="small" onClick={saveRules} disabled={isSaving}>
            Regeln speichern
          </Button>
          <Button size="small" onClick={() => setEdited(null)} disabled={isSaving}>
            Verwerfen
          </Button>
        </Stack>
      </Box>
    </PageSection>
  )
}
