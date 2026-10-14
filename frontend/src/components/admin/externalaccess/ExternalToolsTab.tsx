import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { useLocation, useSearchParams } from 'react-router'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Skeleton from '@mui/material/Skeleton'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import type {
  ExternalAccessLibraryResponse,
  ExternalAccessSettingsResponse,
} from '@/shared/api/types'
import {
  getExternalAccessLibraries,
  getExternalAccessSettings,
  listAllExternalAccessTokens,
  updateExternalAccessSettings,
} from '../../../services/externalAccessApi'
import { confirmAction } from '../../../stores/confirmStore'
import { notify } from '../../../stores/notificationStore'
import BusyButton from '../../a11y/BusyButton'
import SectionHead from '../../SectionHead'
import StatusLine from '../../StatusLine'
import { fontFamily } from '../../../theme/tokens'
import ExternalAccessSettingsPanel, {
  SAVED_MESSAGE,
  SWITCH_CONSEQUENCE,
} from './ExternalAccessSettingsPanel'
import ExternalAccessTokenAdminSection from './ExternalAccessTokenAdminSection'
import OwnTokensLink from './OwnTokensLink'
import ReleasedLibrariesSection from './ReleasedLibrariesSection'
import { channelStateOf } from './externalToolsState'

/** The query parameter that opens the settings panel, e.g. from a bookmark of the former page. */
export const PANEL_PARAM = 'panel'
export const SETTINGS_PANEL = 'settings'
/** Anchors of the two lists, for links into the tab. */
export const LIBRARIES_ANCHOR = 'libraries'
export const TOKENS_ANCHOR = 'tokens'

function plural(value: number, one: string, many: string): string {
  return value === 1 ? `1 ${one}` : `${value} ${many}`
}

interface Step {
  key: string
  text: ReactNode
  state: string | null
}

/** The three keys that must fit before a tool reaches anything, each with its current figure. */
function Steps({ steps }: { steps: Step[] }) {
  return (
    <Box component="section" aria-labelledby="external-tools-steps-heading" sx={{ mb: 4 }}>
      <SectionHead id="external-tools-steps-heading">So kommt ein Werkzeug an Inhalte</SectionHead>
      <Box component="ol" sx={{ listStyle: 'none', p: 0, m: 0 }}>
        {steps.map((step, index) => (
          <Box
            component="li"
            key={step.key}
            sx={{
              display: 'grid',
              gridTemplateColumns: { xs: '28px 1fr', sm: '28px 1fr auto' },
              columnGap: 1.5,
              rowGap: 0.25,
              alignItems: 'baseline',
              py: 1,
              '& + &': { borderTop: 1, borderColor: 'divider' },
            }}
          >
            <Typography
              component="span"
              aria-hidden="true"
              sx={{ fontFamily: fontFamily.mono, fontSize: 12, color: 'text.secondary' }}
            >
              {String(index + 1).padStart(2, '0')}
            </Typography>
            <Typography component="span" sx={{ fontSize: 13.5 }}>
              {step.text}
            </Typography>
            <Typography
              component="span"
              sx={{
                gridColumn: { xs: 2, sm: 3 },
                fontSize: 13,
                fontWeight: 600,
                color: 'text.secondary',
                whiteSpace: 'nowrap',
              }}
            >
              {step.state ?? '…'}
            </Typography>
          </Box>
        ))}
      </Box>
    </Box>
  )
}

/**
 * Der Reiter „Externe KI-Werkzeuge“ der Anbindungen: der Zustand des Zugriffswegs mit Notaus, die
 * drei Schlüssel, die zusammenpassen müssen (Schalter, Freigabe der Bibliothek, Zugangstoken),
 * die freigegebenen Bibliotheken und die Bestandsliste der Zugangstokens. Die Einstellungen
 * stehen im Seitenpanel; `?panel=settings` öffnet es, `#libraries` und `#tokens` führen zur Liste.
 */
export default function ExternalToolsTab() {
  const location = useLocation()
  const [searchParams, setSearchParams] = useSearchParams()
  const [settings, setSettings] = useState<ExternalAccessSettingsResponse | null>(null)
  const [settingsError, setSettingsError] = useState<string | null>(null)
  const [libraries, setLibraries] = useState<ExternalAccessLibraryResponse[] | null>(null)
  const [librariesError, setLibrariesError] = useState<string | null>(null)
  const [activeTokens, setActiveTokens] = useState<number | 'failed' | null>(null)
  const [switchingOff, setSwitchingOff] = useState(false)
  const panelOpen = searchParams.get(PANEL_PARAM) === SETTINGS_PANEL

  // Kein synchrones setState im Rumpf: Die Effekte unten rufen diese Funktionen, und ein
  // synchrones setState von dort erzeugt eine Renderkaskade (react-hooks/set-state-in-effect).
  const loadSettings = useCallback(
    () =>
      getExternalAccessSettings()
        .then((loaded) => {
          setSettings(loaded)
          setSettingsError(null)
        })
        .catch((err: unknown) =>
          setSettingsError(
            err instanceof Error ? err.message : 'Die Einstellungen konnten nicht geladen werden.',
          ),
        ),
    [],
  )

  const loadLibraries = useCallback(
    () =>
      getExternalAccessLibraries()
        .then((loaded) => {
          setLibraries(loaded)
          setLibrariesError(null)
        })
        .catch((err: unknown) =>
          setLibrariesError(
            err instanceof Error ? err.message : 'Die Liste konnte nicht geladen werden.',
          ),
        ),
    [],
  )

  // Only the number of valid tokens is read here; the list below loads its own selection.
  const loadActiveTokens = useCallback(
    () =>
      listAllExternalAccessTokens({ status: 'ACTIVE' })
        .then((loaded) => setActiveTokens(loaded.length))
        .catch(() => setActiveTokens('failed')),
    [],
  )

  useEffect(() => {
    void loadSettings()
    void loadLibraries()
    void loadActiveTokens()
  }, [loadSettings, loadLibraries, loadActiveTokens])

  const anchor = location.hash.replace(/^#/, '')
  // Jump only once the parts above the list have their height, loaded or failed.
  const aboveSettled =
    (settings !== null || settingsError !== null) && (libraries !== null || librariesError !== null)
  const anchorReady = (anchor === TOKENS_ANCHOR || anchor === LIBRARIES_ANCHOR) && aboveSettled
  useEffect(() => {
    if (anchorReady) document.getElementById(anchor)?.scrollIntoView?.({ block: 'start' })
  }, [anchor, anchorReady])

  function setPanel(open: boolean) {
    const next = new URLSearchParams(searchParams)
    if (open) next.set(PANEL_PARAM, SETTINGS_PANEL)
    else next.delete(PANEL_PARAM)
    // Opening and closing both replace the entry: „Zurück" leaves the tab rather than closing
    // the panel past its question about unsaved changes.
    setSearchParams(next, { replace: true, preventScrollReset: true })
  }

  async function switchOff() {
    if (!settings) return
    const confirmed = await confirmAction({
      question: 'Externe KI-Werkzeuge jetzt ausschalten?',
      consequence: SWITCH_CONSEQUENCE,
      confirmLabel: 'Ausschalten',
      tone: 'danger',
    })
    if (!confirmed) return
    setSwitchingOff(true)
    try {
      // The update replaces the settings wholesale; read them right before, so a change made
      // elsewhere since this page loaded is kept and only the switch is turned.
      const current = await getExternalAccessSettings()
      const stored = await updateExternalAccessSettings({
        enabled: false,
        tokenMaxLifetimeDays: current.tokenMaxLifetimeDays,
        tokenRateLimitPerHour: current.tokenRateLimitPerHour,
        allowedCidrs: current.allowedCidrs,
        massRetrievalAlertThreshold: current.massRetrievalAlertThreshold,
        serverInstructions: current.serverInstructions,
      })
      setSettings(stored)
      notify(
        'Externe KI-Werkzeuge sind ausgeschaltet. Das wirkt ab dem nächsten Aufruf.',
        'success',
      )
    } catch (err: unknown) {
      notify(err instanceof Error ? err.message : 'Das Ausschalten ist fehlgeschlagen.', 'error')
    } finally {
      setSwitchingOff(false)
    }
  }

  const state = settings ? channelStateOf(settings) : null
  const steps: Step[] = [
    {
      key: 'switch',
      text: 'Sie lassen externe KI-Werkzeuge hier zu.',
      state: settingsError
        ? 'nicht geladen'
        : settings
          ? settings.enabled
            ? 'zugelassen'
            : 'nicht zugelassen'
          : null,
    },
    {
      key: 'release',
      text: 'Wer eine Bibliothek verwaltet, gibt sie befristet frei – in der Bibliothek unter „Freigaben“.',
      state: librariesError
        ? 'Zahl nicht geladen'
        : libraries
          ? plural(libraries.length, 'Bibliothek', 'Bibliotheken')
          : null,
    },
    {
      key: 'token',
      text: (
        <>
          Jede Person erzeugt ihr Zugangstoken selbst unter <OwnTokensLink /> – für freigegebene
          Bibliotheken, die sie lesen darf.
        </>
      ),
      state:
        activeTokens === null
          ? null
          : activeTokens === 'failed'
            ? 'Zahl nicht geladen'
            : plural(activeTokens, 'gültiges Token', 'gültige Tokens'),
    },
  ]

  return (
    <>
      <Typography sx={{ fontSize: 13.5, color: 'text.secondary', mb: 2.5, maxWidth: 760 }}>
        Externe KI-Werkzeuge, etwa ein Entwicklungsassistent oder ein eigenes Skript, lesen über
        diesen Weg Fundstellen aus freigegebenen Bibliotheken - mit den Rechten der Person, der das
        Zugangstoken gehört. In anderen Teilen von OPAA heißt dieser Weg „Fremdzugang“.
      </Typography>

      <Box sx={{ mb: 4 }}>
        {settingsError ? (
          <Alert
            severity="error"
            action={
              <Button color="inherit" size="small" onClick={() => void loadSettings()}>
                Erneut laden
              </Button>
            }
          >
            {settingsError}
          </Alert>
        ) : !state || !settings ? (
          <Skeleton variant="rounded" height={56} />
        ) : (
          <Box
            sx={{
              display: 'flex',
              gap: 2,
              rowGap: 1.5,
              alignItems: 'flex-start',
              flexWrap: { xs: 'wrap', md: 'nowrap' },
            }}
          >
            <Box sx={{ flex: '1 1 320px', minWidth: 0 }}>
              <StatusLine
                component="h2"
                label="Zustand des Zugriffswegs"
                tone={state.tone}
                headline={state.headline}
                detail={state.detail}
              />
            </Box>
            <Stack direction="row" spacing={1} sx={{ flex: 'none' }}>
              {settings.enabled && (
                <BusyButton
                  variant="outlined"
                  color="error"
                  busy={switchingOff}
                  busyAnnouncement="Wird ausgeschaltet"
                  onClick={() => void switchOff()}
                >
                  Ausschalten
                </BusyButton>
              )}
              <Button
                variant={settings.enabled ? 'text' : 'contained'}
                onClick={() => setPanel(true)}
              >
                Einstellungen
              </Button>
            </Stack>
          </Box>
        )}
      </Box>

      <Steps steps={steps} />

      <Box id={LIBRARIES_ANCHOR} sx={{ scrollMarginTop: 16 }}>
        <ReleasedLibrariesSection
          libraries={libraries}
          error={librariesError}
          onRetry={() => void loadLibraries()}
        />
      </Box>

      <Box id={TOKENS_ANCHOR} sx={{ scrollMarginTop: 16 }}>
        <ExternalAccessTokenAdminSection onChanged={() => void loadActiveTokens()} />
      </Box>

      <ExternalAccessSettingsPanel
        settings={panelOpen ? settings : null}
        onClose={() => setPanel(false)}
        onSaved={(stored) => {
          setSettings(stored)
          setPanel(false)
          notify(SAVED_MESSAGE, 'success')
        }}
      />
    </>
  )
}
