import { useId, useState } from 'react'
import { useNavigate } from 'react-router'
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
import ChecklistOutlinedIcon from '@mui/icons-material/ChecklistOutlined'
import type { OidcProviderResponse } from '../../../types/api'
import { apiErrorMessage } from '../../../services/apiErrorDetails'
import { confirmAction } from '../../../stores/confirmStore'
import { notify } from '../../../stores/notificationStore'
import { useOidcProviderStore } from '../../../stores/oidcProviderStore'
import { PROVIDER_CONFLICT_MESSAGES } from '../oidcProviderConflicts'
import ProviderShutdownDialog, { type ProviderShutdownAction } from './ProviderShutdownDialog'

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
 * geht erst hinaus, nachdem der Absatz gelesen wurde. Dasselbe gilt für die Folgen für verbundene
 * Konten ({@link ProviderShutdownDialog}).
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
  const navigate = useNavigate()
  const [anchor, setAnchor] = useState<HTMLElement | null>(null)
  const [busy, setBusy] = useState(false)
  const [shutdown, setShutdown] = useState<ProviderShutdownAction | null>(null)
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
      setShutdown('disable')
      return
    }
    // Das Einschalten fragt nicht - es nimmt niemandem die Anmeldung.
    await run(
      () => setProviderEnabled(provider.id, true),
      `„${provider.displayName}“ wurde aktiviert.`,
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

  function remove() {
    close()
    setShutdown('delete')
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
        <MenuItem
          onClick={() => {
            close()
            void navigate(`/admin/identity-providers/${provider.id}/groups`)
          }}
        >
          <ListItemIcon>
            <ChecklistOutlinedIcon fontSize="small" />
          </ListItemIcon>
          Arbeitsliste der Gruppen
        </MenuItem>
        <Divider />
        <MenuItem
          onClick={remove}
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
      {shutdown && (
        <ProviderShutdownDialog
          provider={provider}
          action={shutdown}
          isLastEnabled={isLastEnabled}
          onClose={() => setShutdown(null)}
        />
      )}
    </>
  )
}
