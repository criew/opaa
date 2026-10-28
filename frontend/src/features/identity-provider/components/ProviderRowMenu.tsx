import { useId, useState } from 'react'
import { useNavigate } from 'react-router'
import Box from '@mui/material/Box'
import Divider from '@mui/material/Divider'
import IconButton from '@mui/material/IconButton'
import ListItemIcon from '@mui/material/ListItemIcon'
import Menu from '@mui/material/Menu'
import MenuItem from '@mui/material/MenuItem'
import Typography from '@mui/material/Typography'
import Tooltip from '@mui/material/Tooltip'
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutlined'
import EditOutlinedIcon from '@mui/icons-material/EditOutlined'
import MoreHorizIcon from '@mui/icons-material/MoreHoriz'
import PowerSettingsNewOutlinedIcon from '@mui/icons-material/PowerSettingsNewOutlined'
import StarOutlineRoundedIcon from '@mui/icons-material/StarOutlineRounded'
import ChecklistOutlinedIcon from '@mui/icons-material/ChecklistOutlined'
import type { OidcProviderResponse } from '@/shared/api/types'
import { apiErrorMessage } from '@/shared/api/apiErrorDetails'
import { confirmAction } from '@/shared/stores/confirmStore'
import { notify } from '@/shared/stores/notificationStore'
import { useOidcProviderStore } from '../stores/oidcProviderStore'
import { PROVIDER_CONFLICT_MESSAGES } from './oidcProviderConflicts'
import ProviderShutdownDialog, { type ProviderShutdownAction } from './ProviderShutdownDialog'

export const DEFAULT_CONSEQUENCE =
  'Der Standardanbieter ist der einzige, der weder deaktiviert noch gelöscht werden kann; die ' +
  'Erstadministrator-Regel und der Verzeichnisabgleich gelten nur für seine Konten.'

/** Why two actions are missing on the default provider — as text, not as a tooltip. */
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
 * The row menu of an identity provider.
 *
 * Two rules of the area live here. First: the default provider can neither be
 * deactivated nor deleted as long as another one exists — the entries are then **disabled and
 * name their reason** instead of simply being missing. A missing action raises the question
 * whether it was ever there; a disabled one answers it.
 *
 * Second: for the last enabled provider **the confirmation is the acknowledgement** the
 * backend requires (ADR-0025). The additional paragraph and the flag therefore belong together —
 * the flag goes out only after the paragraph was read. The same applies to the consequences for
 * connected accounts ({@link ProviderShutdownDialog}).
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
    // Switching on does not ask - it takes nobody's login away.
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
      <Tooltip title="Weitere Aktionen">
        <span>
          <IconButton
            size="small"
            onClick={(e) => setAnchor(e.currentTarget)}
            disabled={busy}
            aria-haspopup="true"
            aria-label={`Aktionen für „${provider.displayName}“`}
          >
            <MoreHorizIcon fontSize="small" />
          </IconButton>
        </span>
      </Tooltip>
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
          // An `<li role="presentation">` instead of a paragraph: a `<p>` under `role="menu"`
          // violates `aria-required-children`. The disabled entries refer here via
          // `aria-describedby`, so that the reason is read out as well.
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
