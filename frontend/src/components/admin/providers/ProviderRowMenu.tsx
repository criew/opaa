import { useId, useState } from 'react'
import Box from '@mui/material/Box'
import Divider from '@mui/material/Divider'
import IconButton from '@mui/material/IconButton'
import ListItemIcon from '@mui/material/ListItemIcon'
import Menu from '@mui/material/Menu'
import MenuItem from '@mui/material/MenuItem'
import Typography from '@mui/material/Typography'
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutlined'
import EditOutlinedIcon from '@mui/icons-material/EditOutlined'
import MoreVertIcon from '@mui/icons-material/MoreVert'
import PowerSettingsNewOutlinedIcon from '@mui/icons-material/PowerSettingsNewOutlined'
import StarOutlineRoundedIcon from '@mui/icons-material/StarOutlineRounded'
import type { OidcProviderResponse } from '../../../types/api'
import { apiErrorMessage } from '../../../services/apiErrorDetails'
import { confirmAction } from '../../../stores/confirmStore'
import { notify } from '../../../stores/notificationStore'
import { useOidcProviderStore } from '../../../stores/oidcProviderStore'
import { PROVIDER_CONFLICT_MESSAGES } from '../oidcProviderConflicts'

export const DISABLE_CONSEQUENCE =
  'Nutzer dieses Anbieters können sich ab sofort nicht mehr anmelden; laufende Sitzungen enden ' +
  'mit der nächsten Anfrage. Die Konten und ihre Rechte bleiben erhalten.'

export const DELETE_CONSEQUENCE =
  'Nutzer dieses Anbieters können sich nicht mehr anmelden. Die Konten bleiben erhalten und ' +
  'werden wieder nutzbar, sobald ein Anbieter mit derselben Issuer-URI existiert.'

export const LAST_PROVIDER_CONSEQUENCE =
  'Dies ist der letzte aktivierte Identitätsanbieter. Danach können sich nur noch lokale Konten ' +
  'anmelden – über die Anmeldeseite und, für die Systemverwaltung, über /login/system. Ein ' +
  'vertippter Anbieter lässt sich aus der lokalen Anmeldung heraus korrigieren, ohne ' +
  'Datenbankzugriff und ohne Umgebungsvariable.'

export const DEFAULT_CONSEQUENCE =
  'Der Standardanbieter ist der einzige, der weder deaktiviert noch gelöscht werden kann; die ' +
  'Erstadministrator-Regel und der Verzeichnisabgleich gelten nur für seine Konten.'

/** Warum am Standardanbieter zwei Handlungen fehlen — als Text, nicht als Tooltip. */
export const DEFAULT_PROVIDER_HINT =
  'Der Standardanbieter lässt sich weder deaktivieren noch löschen. Machen Sie zuerst einen ' +
  'anderen aktivierten Anbieter zum Standard – als letzter Anbieter ist auch er beides.'

interface ProviderRowMenuProps {
  provider: OidcProviderResponse
  /** The one enabled provider left: its confirmation *is* the backend's acknowledgement. */
  isLastEnabled: boolean
  canDisable: boolean
  canDelete: boolean
  onEdit: (provider: OidcProviderResponse) => void
}

/**
 * Das Zeilenmenü eines Identitätsanbieters (#1625).
 *
 * Zwei Regeln des Bereichs stecken hier drin. Erstens: Der Standardanbieter lässt sich weder
 * deaktivieren noch löschen, solange ein anderer existiert — die Einträge sind dann **gesperrt und
 * nennen ihren Grund**, statt einfach zu fehlen. Eine fehlende Handlung wirft die Frage auf, ob
 * sie je da war; eine gesperrte beantwortet sie.
 *
 * Zweitens: Beim letzten aktivierten Anbieter **ist die Rückfrage das Acknowledgement**, das das
 * Backend verlangt (ADR-0025). Der Zusatzabsatz und das Flag gehören deshalb zusammen — das Flag
 * geht erst hinaus, nachdem der Absatz gelesen wurde.
 */
export default function ProviderRowMenu({
  provider,
  isLastEnabled,
  canDisable,
  canDelete,
  onEdit,
}: ProviderRowMenuProps) {
  const setProviderEnabled = useOidcProviderStore((s) => s.setProviderEnabled)
  const makeProviderDefault = useOidcProviderStore((s) => s.makeProviderDefault)
  const deleteExistingProvider = useOidcProviderStore((s) => s.deleteExistingProvider)
  const [anchor, setAnchor] = useState<HTMLElement | null>(null)
  const [busy, setBusy] = useState(false)
  const reasonId = useId()

  const close = () => setAnchor(null)
  const gesperrt = !canDisable || !canDelete

  async function run(action: () => Promise<unknown>, erfolg: string, fallback: string) {
    setBusy(true)
    try {
      await action()
      notify(erfolg, 'success')
    } catch (err) {
      notify(apiErrorMessage(err, PROVIDER_CONFLICT_MESSAGES, fallback), 'error')
    } finally {
      setBusy(false)
    }
  }

  async function toggleEnabled() {
    close()
    if (provider.enabled) {
      const consequence = isLastEnabled
        ? `${DISABLE_CONSEQUENCE}\n\n${LAST_PROVIDER_CONSEQUENCE}`
        : DISABLE_CONSEQUENCE
      if (
        !(await confirmAction({
          question: `„${provider.displayName}“ deaktivieren?`,
          consequence,
          confirmLabel: 'Deaktivieren',
          tone: 'caution',
        }))
      ) {
        return
      }
    }
    // Das Einschalten fragt nicht - es nimmt niemandem die Anmeldung.
    await run(
      () => setProviderEnabled(provider.id, !provider.enabled, isLastEnabled),
      provider.enabled
        ? `„${provider.displayName}“ wurde deaktiviert.`
        : `„${provider.displayName}“ wurde aktiviert.`,
      'Änderung fehlgeschlagen',
    )
  }

  async function makeDefault() {
    close()
    if (
      !(await confirmAction({
        question: `„${provider.displayName}“ zum Standardanbieter machen?`,
        consequence: DEFAULT_CONSEQUENCE,
        confirmLabel: 'Zum Standard machen',
        tone: 'caution',
      }))
    ) {
      return
    }
    await run(
      () => makeProviderDefault(provider.id),
      `„${provider.displayName}“ ist jetzt der Standardanbieter.`,
      'Änderung fehlgeschlagen',
    )
  }

  async function remove() {
    close()
    const consequence = isLastEnabled
      ? `${DELETE_CONSEQUENCE}\n\n${LAST_PROVIDER_CONSEQUENCE}`
      : DELETE_CONSEQUENCE
    if (
      !(await confirmAction({
        question: `„${provider.displayName}“ löschen?`,
        consequence,
        confirmLabel: 'Löschen',
        tone: 'danger',
      }))
    ) {
      return
    }
    await run(
      () => deleteExistingProvider(provider.id, isLastEnabled),
      `„${provider.displayName}“ wurde gelöscht.`,
      'Löschen fehlgeschlagen',
    )
  }

  return (
    <>
      <IconButton
        size="small"
        onClick={(e) => setAnchor(e.currentTarget)}
        disabled={busy}
        aria-haspopup="true"
        aria-label={`Aktionen für „${provider.displayName}“`}
      >
        <MoreVertIcon fontSize="small" />
      </IconButton>
      <Menu
        anchorEl={anchor}
        open={anchor !== null}
        onClose={close}
        slotProps={{ list: { 'aria-label': `Aktionen für „${provider.displayName}“` } }}
      >
        <MenuItem
          onClick={() => {
            close()
            onEdit(provider)
          }}
        >
          <ListItemIcon>
            <EditOutlinedIcon fontSize="small" />
          </ListItemIcon>
          Bearbeiten
        </MenuItem>
        <MenuItem
          onClick={() => void toggleEnabled()}
          disabled={!canDisable}
          aria-describedby={!canDisable ? reasonId : undefined}
        >
          <ListItemIcon>
            <PowerSettingsNewOutlinedIcon fontSize="small" />
          </ListItemIcon>
          {provider.enabled ? 'Deaktivieren' : 'Aktivieren'}
        </MenuItem>
        {!provider.isDefault && provider.enabled && (
          <MenuItem onClick={() => void makeDefault()}>
            <ListItemIcon>
              <StarOutlineRoundedIcon fontSize="small" />
            </ListItemIcon>
            Zum Standard machen
          </MenuItem>
        )}
        <Divider />
        <MenuItem
          onClick={() => void remove()}
          disabled={!canDelete}
          aria-describedby={!canDelete ? reasonId : undefined}
        >
          <ListItemIcon>
            <DeleteOutlineIcon fontSize="small" color={canDelete ? 'error' : undefined} />
          </ListItemIcon>
          <Typography sx={{ fontSize: 14, color: canDelete ? 'error.main' : undefined }}>
            Löschen
          </Typography>
        </MenuItem>
        {gesperrt && (
          // Ein `<li role="presentation">` statt eines Absatzes: Ein `<p>` unter `role="menu"`
          // verletzt `aria-required-children`. Die gesperrten Einträge verweisen über
          // `aria-describedby` hierher, damit die Begründung auch vorgelesen wird.
          <Box
            component="li"
            role="presentation"
            id={reasonId}
            sx={{ px: 2, py: 1, maxWidth: 340 }}
          >
            <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>
              {DEFAULT_PROVIDER_HINT}
            </Typography>
          </Box>
        )}
      </Menu>
    </>
  )
}
