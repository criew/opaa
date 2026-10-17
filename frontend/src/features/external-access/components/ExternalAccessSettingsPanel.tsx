import { useEffect, useRef, useState, type ReactNode } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Drawer from '@mui/material/Drawer'
import FormControlLabel from '@mui/material/FormControlLabel'
import IconButton from '@mui/material/IconButton'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import CloseIcon from '@mui/icons-material/Close'
import type {
  ExternalAccessSettingsResponse,
  ExternalAccessSettingsUpdateRequest,
} from '@/shared/api/types'
import { updateExternalAccessSettings } from '../api/externalAccessApi'
import { apiFieldErrors } from '@/shared/api/apiErrorDetails'
import { confirmAction } from '@/shared/stores/confirmStore'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import SectionHead from '@/shared/ui/SectionHead'
import { formatDate } from './tokenLabels'

export const SWITCH_CONSEQUENCE =
  'Beschäftigte können Zugangstokens für externe KI-Werkzeuge erzeugen. Das Ausschalten wirkt ' +
  'sofort und bei jedem Aufruf; laufende Verbindungen enden. Die Tokens bleiben erhalten und ' +
  'wirken beim Wiedereinschalten weiter.'

export const CODETERMINATION_HINT =
  'Vor dem Einschalten: Die Beteiligung der Personalvertretung ist zu klären, und die ' +
  'Beschäftigten sind über diesen Zugriffsweg, die dabei erhobenen Angaben und die Freiwilligkeit ' +
  'der Nutzung zu unterrichten. Aus der Nichtnutzung entsteht kein Nachteil.'

export const SAVED_MESSAGE =
  'Die Einstellungen wurden gespeichert und wirken ab dem nächsten Aufruf.'

interface Draft {
  enabled: boolean
  tokenMaxLifetimeDays: string
  tokenRateLimitPerHour: string
  allowedCidrs: string
  massRetrievalAlertThreshold: string
  serverInstructions: string
}

function draftOf(settings: ExternalAccessSettingsResponse): Draft {
  return {
    enabled: settings.enabled,
    tokenMaxLifetimeDays: String(settings.tokenMaxLifetimeDays),
    tokenRateLimitPerHour: String(settings.tokenRateLimitPerHour),
    allowedCidrs: settings.allowedCidrs.join('\n'),
    massRetrievalAlertThreshold: String(settings.massRetrievalAlertThreshold),
    serverInstructions: settings.serverInstructions,
  }
}

function parseCidrs(value: string): string[] {
  return value
    .split(/[,;\s]+/)
    .map((entry) => entry.trim())
    .filter((entry) => entry !== '')
}

function requestOf(draft: Draft): ExternalAccessSettingsUpdateRequest {
  return {
    enabled: draft.enabled,
    tokenMaxLifetimeDays: Number(draft.tokenMaxLifetimeDays),
    tokenRateLimitPerHour: Number(draft.tokenRateLimitPerHour),
    allowedCidrs: parseCidrs(draft.allowedCidrs),
    massRetrievalAlertThreshold: Number(draft.massRetrievalAlertThreshold),
    serverInstructions: draft.serverInstructions,
  }
}

function sameDraft(a: Draft, b: Draft): boolean {
  return (Object.keys(a) as Array<keyof Draft>).every((key) => a[key] === b[key])
}

/**
 * The saved state deliberately stands next to and not in the switch label: as long as a
 * change is not saved yet, a name „derzeit aus“ next to `aria-checked="true"` would report two
 * different states right at the control that opens the path.
 */
function storedStateLine(settings: ExternalAccessSettingsResponse): string {
  return `Gespeichert: ${settings.enabled ? 'ein' : 'aus'} — zuletzt geändert am ${formatDate(settings.updatedAt)} durch ${settings.updatedBy ?? 'die Installation selbst'}`
}

function PanelSection({ id, title, children }: { id: string; title: string; children: ReactNode }) {
  return (
    <Box component="section" aria-labelledby={id}>
      <SectionHead id={id} component="h3">
        {title}
      </SectionHead>
      {children}
    </Box>
  )
}

interface ExternalAccessSettingsPanelProps {
  /** The stored settings; null keeps the panel closed. */
  settings: ExternalAccessSettingsResponse | null
  onClose: () => void
  onSaved: (stored: ExternalAccessSettingsResponse) => void
}

/**
 * The settings for external AI tools in the side panel: the installation-wide switch, the
 * limits including network ranges and the introduction text for external tools. Everything is
 * saved together because the interface replaces the settings as a whole.
 *
 * State and time of the last change are at the top: a restore from a backup rolls back
 * switch, releases and tokens together - including the log that would prove the emergency
 * stop. A visible change date makes such a backdating recognizable.
 */
export default function ExternalAccessSettingsPanel({
  settings,
  onClose,
  onSaved,
}: ExternalAccessSettingsPanelProps) {
  // Escape and the backdrop ask the form, which alone knows whether something is unsaved. The
  // form's state - and with it „unsaved" - ends whenever the panel closes, however it closes.
  const requestCloseRef = useRef<(() => void) | null>(null)

  return (
    <Drawer
      anchor="right"
      open={settings !== null}
      onClose={() => requestCloseRef.current?.()}
      slotProps={{
        paper: {
          role: 'dialog',
          'aria-labelledby': 'external-tools-settings-heading',
          sx: { width: { xs: '100%', sm: 560 }, display: 'flex', flexDirection: 'column' },
        },
      }}
    >
      {settings && (
        <SettingsForm
          key={settings.updatedAt}
          settings={settings}
          requestCloseRef={requestCloseRef}
          onClose={onClose}
          onSaved={onSaved}
        />
      )}
    </Drawer>
  )
}

function SettingsForm({
  settings,
  requestCloseRef,
  onClose,
  onSaved,
}: {
  settings: ExternalAccessSettingsResponse
  requestCloseRef: { current: (() => void) | null }
  onClose: () => void
  onSaved: (stored: ExternalAccessSettingsResponse) => void
}) {
  const [initial] = useState(() => draftOf(settings))
  const [draft, setDraft] = useState<Draft>(initial)
  const [error, setError] = useState<string | null>(null)
  const [fieldMessages, setFieldMessages] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)
  const errorRef = useRef<HTMLDivElement>(null)
  const dirty = !sameDraft(draft, initial)

  async function requestClose() {
    if (busy) return
    if (
      dirty &&
      !(await confirmAction({
        question: 'Ungespeicherte Änderungen verwerfen?',
        consequence: 'Die Einstellungen bleiben, wie sie zuletzt gespeichert wurden.',
        confirmLabel: 'Verwerfen',
        cancelLabel: 'Weiter bearbeiten',
        tone: 'caution',
      }))
    ) {
      return
    }
    onClose()
  }

  useEffect(() => {
    requestCloseRef.current = () => void requestClose()
    return () => {
      requestCloseRef.current = null
    }
  })

  // After a failed save the focus goes to the message (docs/design/accessibility.md, 2.7).
  useEffect(() => {
    if (error) errorRef.current?.focus()
  }, [error])

  function update(change: Partial<Draft>) {
    setDraft((current) => ({ ...current, ...change }))
  }

  async function save() {
    setError(null)
    setFieldMessages({})
    setBusy(true)
    try {
      onSaved(await updateExternalAccessSettings(requestOf(draft)))
    } catch (err: unknown) {
      const perField = apiFieldErrors(err)
      setFieldMessages(Object.fromEntries(perField.map((e) => [e.field, e.message])))
      setError(err instanceof Error ? err.message : 'Speichern fehlgeschlagen.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Box
      component="form"
      noValidate
      onSubmit={(event) => {
        event.preventDefault()
        void save()
      }}
      sx={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}
    >
      <Box
        sx={{
          display: 'flex',
          alignItems: 'flex-start',
          gap: 1,
          px: 3,
          pt: 2.5,
          pb: 2,
          borderBottom: 1,
          borderColor: 'divider',
        }}
      >
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <Typography
            id="external-tools-settings-heading"
            component="h2"
            sx={{ fontSize: 18, fontWeight: 600 }}
          >
            Einstellungen für externe KI-Werkzeuge
          </Typography>
          <Typography sx={{ fontSize: 13, color: 'text.secondary', mt: 0.5 }}>
            Gilt für die ganze Installation. Jede Änderung außer dem Einleitungstext wird
            protokolliert.
          </Typography>
        </Box>
        <IconButton
          aria-label="Schließen"
          onClick={() => void requestClose()}
          disabled={busy}
          sx={{ mt: -0.5 }}
        >
          <CloseIcon />
        </IconButton>
      </Box>

      <Stack spacing={3.5} sx={{ flex: 1, minHeight: 0, overflowY: 'auto', px: 3, py: 2.5 }}>
        {error && (
          <Alert ref={errorRef} tabIndex={-1} severity="error" onClose={() => setError(null)}>
            {error}
          </Alert>
        )}

        <PanelSection id="external-tools-settings-switch" title="Ein/Aus">
          <FormControlLabel
            control={
              <Switch
                checked={draft.enabled}
                onChange={(event) => update({ enabled: event.target.checked })}
              />
            }
            label="Externe KI-Werkzeuge zulassen"
          />
          <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 0.5 }}>
            {storedStateLine(settings)}
          </Typography>
          <Typography sx={{ fontSize: 13, mt: 1.5 }}>{SWITCH_CONSEQUENCE}</Typography>
          <Alert severity="info" sx={{ mt: 1.5 }}>
            {CODETERMINATION_HINT}
          </Alert>
        </PanelSection>

        <PanelSection id="external-tools-settings-limits" title="Grenzen">
          <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mb: 2 }}>
            Laufzeit und Anfragen gelten je Zugangstoken, Abflussalarm und Netzbereiche für alle
            Zugriffe zusammen. Die Anfragegrenze ist eine Lastbremse, kein Schutz vor Massenabfluss:
            Was in einer Nacht nicht geht, geht in neunzig Tagen.
          </Typography>
          <Stack spacing={2.5}>
            <TextField
              label="Zugangstokens höchstens gültig (Tage)"
              type="number"
              size="small"
              value={draft.tokenMaxLifetimeDays}
              onChange={(event) => update({ tokenMaxLifetimeDays: event.target.value })}
              error={Boolean(fieldMessages.tokenMaxLifetimeDays)}
              helperText={fieldMessages.tokenMaxLifetimeDays ?? 'Zwischen 1 und 365 Tagen.'}
              sx={{ maxWidth: 340 }}
            />
            <TextField
              label="Anfragen je Token und Stunde"
              type="number"
              size="small"
              value={draft.tokenRateLimitPerHour}
              onChange={(event) => update({ tokenRateLimitPerHour: event.target.value })}
              error={Boolean(fieldMessages.tokenRateLimitPerHour)}
              helperText={fieldMessages.tokenRateLimitPerHour}
              sx={{ maxWidth: 340 }}
            />
            <TextField
              label="Abflussalarm ab (Abrufe je Stunde)"
              type="number"
              size="small"
              value={draft.massRetrievalAlertThreshold}
              onChange={(event) => update({ massRetrievalAlertThreshold: event.target.value })}
              error={Boolean(fieldMessages.massRetrievalAlertThreshold)}
              helperText={
                fieldMessages.massRetrievalAlertThreshold ??
                'Die Überschreitung meldet sich der Systemverwaltung; ein Nachweisprotokoll entsteht daraus nicht.'
              }
              sx={{ maxWidth: 340 }}
            />
            <TextField
              label="Netzbereiche"
              value={draft.allowedCidrs}
              onChange={(event) => update({ allowedCidrs: event.target.value })}
              error={Boolean(fieldMessages.allowedCidrs)}
              helperText={
                fieldMessages.allowedCidrs ??
                'Eine Netzangabe je Zeile (z. B. 10.0.0.0/8). Voreinstellung ist das Hausnetz. Eine leere Liste schließt den Zugriff für jede Adresse. Geprüft wird die über die Trusted-Proxy-Auflösung ermittelte Adresse (OPAA_RATE_LIMIT_TRUSTED_PROXY_CIDRS).'
              }
              multiline
              minRows={3}
              fullWidth
            />
          </Stack>
        </PanelSection>

        <PanelSection
          id="external-tools-settings-instructions"
          title="Einleitungstext für externe KI-Werkzeuge"
        >
          <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mb: 2 }}>
            Er wird dem Modell des externen KI-Werkzeugs beim Verbindungsaufbau mitgegeben, damit es
            erkennt, welche Fragen hierher gehören. Seine Änderung ändert keine Reichweite und wird
            nicht protokolliert.
          </Typography>
          <TextField
            label="Einleitungstext"
            value={draft.serverInstructions}
            onChange={(event) => update({ serverInstructions: event.target.value })}
            error={Boolean(fieldMessages.serverInstructions)}
            helperText={fieldMessages.serverInstructions ?? 'Höchstens 4000 Zeichen.'}
            multiline
            minRows={4}
            fullWidth
          />
          <Button
            size="small"
            sx={{ mt: 1 }}
            onClick={() => update({ serverInstructions: settings.defaultServerInstructions })}
            disabled={draft.serverInstructions === settings.defaultServerInstructions}
          >
            Vorgabetext
          </Button>
        </PanelSection>
      </Stack>

      <Box
        sx={{
          display: 'flex',
          justifyContent: 'flex-end',
          gap: 1,
          px: 3,
          py: 2,
          borderTop: 1,
          borderColor: 'divider',
        }}
      >
        <Button onClick={() => void requestClose()} disabled={busy}>
          Abbrechen
        </Button>
        <BusyButton
          type="submit"
          variant="contained"
          busy={busy}
          busyAnnouncement="Einstellungen werden gespeichert"
        >
          Speichern
        </BusyButton>
      </Box>
    </Box>
  )
}
