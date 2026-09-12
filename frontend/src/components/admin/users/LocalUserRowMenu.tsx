import { useState } from 'react'
import Divider from '@mui/material/Divider'
import IconButton from '@mui/material/IconButton'
import ListItemIcon from '@mui/material/ListItemIcon'
import Menu from '@mui/material/Menu'
import MenuItem from '@mui/material/MenuItem'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutlined'
import EditOutlinedIcon from '@mui/icons-material/EditOutlined'
import KeyOutlinedIcon from '@mui/icons-material/KeyOutlined'
import LockOpenOutlinedIcon from '@mui/icons-material/LockOpenOutlined'
import LockOutlinedIcon from '@mui/icons-material/LockOutlined'
import MailOutlinedIcon from '@mui/icons-material/MailOutlined'
import MoreVertIcon from '@mui/icons-material/MoreVert'
import type { LocalUserResponse } from '../../../types/api'
import { notify } from '../../../stores/notificationStore'
import { useUserAdminStore } from '../../../stores/userAdminStore'
import type { SetupLinkHandover } from './SetupLinkDialog'
import { localUserErrorMessage } from './localUserLabels'

export const LOCK_CONSEQUENCE =
  'Das Konto kann sich nicht mehr anmelden, und alle laufenden Sitzungen enden sofort. Die Person ' +
  'wird per E-Mail darüber unterrichtet. Die Inhalte und Rechte des Kontos bleiben erhalten.'

export const RESET_CONSEQUENCE =
  'Alle Sitzungen des Kontos enden, und die Person erhält einen einmaligen Rücksetzlink per ' +
  'E-Mail. Geht die Nachricht nicht hinaus, wird der Link genau einmal zur Übergabe angezeigt.'

export const GENERATE_PASSWORD_CONSEQUENCE =
  'Es wird ein neues Passwort erzeugt und genau einmal angezeigt. Alle Sitzungen des Kontos enden; ' +
  'bei der nächsten Anmeldung muss das Passwort gewechselt werden.'

export const DELETE_CONSEQUENCE =
  'Sperren ist der Regelweg – Löschen die Ausnahme und nicht umkehrbar. Gelöscht wird nur ein ' +
  'Konto, auf das nichts mehr verweist; mit ihm gehen der persönliche Space, die Zugangsdaten und ' +
  'alle Tokens. Das Protokoll behält seine pseudonymen Zeilen.'

export const SELF_ACTION_TOOLTIP =
  'Das eigene Konto kann hier nicht gesperrt oder gelöscht werden – sonst wäre die Verwaltung ' +
  'nach einem Fehlgriff nicht mehr erreichbar.'

export const BOOTSTRAP_DELETE_TOOLTIP =
  'Das Notanker-Konto der Systemverwaltung kann nicht gelöscht werden – es ist der Weg zurück in ' +
  'eine Installation ohne funktionierenden Identitätsanbieter.'

interface LocalUserRowMenuProps {
  user: LocalUserResponse
  /** The caller's own row: locking and deleting it is refused by the backend anyway (409). */
  isSelf: boolean
  onEdit: (user: LocalUserResponse) => void
  onSetupLink: (handover: SetupLinkHandover) => void
  onGeneratedPassword: (user: LocalUserResponse, password: string) => void
}

/**
 * Das Zeilenmenü eines lokalen Kontos (#1541). Jede Handlung bestätigt ihre Konsequenz über
 * `window.confirm` (projektweites Muster), die einmaligen Anzeigen – Link und erzeugtes Passwort –
 * laufen über ihren eigenen Dialog.
 *
 * „Entsperren" erscheint nur an einem gesperrten Konto (sonst 409 `NOT_LOCKED`), „Löschen" steht
 * nachrangig unter einer Trennlinie: Sperren ist der Regelweg (ADR-0033, Entscheidung 11).
 */
export default function LocalUserRowMenu({
  user,
  isSelf,
  onEdit,
  onSetupLink,
  onGeneratedPassword,
}: LocalUserRowMenuProps) {
  const lockUser = useUserAdminStore((s) => s.lockUser)
  const unlockUser = useUserAdminStore((s) => s.unlockUser)
  const deleteUser = useUserAdminStore((s) => s.deleteUser)
  const resetUserPassword = useUserAdminStore((s) => s.resetUserPassword)
  const generateUserPassword = useUserAdminStore((s) => s.generateUserPassword)
  const [anchor, setAnchor] = useState<HTMLElement | null>(null)
  const [busy, setBusy] = useState(false)

  const close = () => setAnchor(null)

  async function run(action: () => Promise<string>, fallback: string) {
    close()
    setBusy(true)
    try {
      notify(await action(), 'success')
    } catch (err) {
      notify(localUserErrorMessage(err, fallback), 'error')
    } finally {
      setBusy(false)
    }
  }

  function lock() {
    if (!window.confirm(`„${user.displayName}“ sperren?\n\n${LOCK_CONSEQUENCE}`)) return close()
    void run(async () => {
      await lockUser(user.id)
      return `„${user.displayName}“ wurde gesperrt.`
    }, 'Das Konto konnte nicht gesperrt werden.')
  }

  function unlock() {
    void run(async () => {
      await unlockUser(user.id)
      return `„${user.displayName}“ wurde entsperrt.`
    }, 'Das Konto konnte nicht entsperrt werden.')
  }

  function resetPassword() {
    if (
      !window.confirm(`Passwort von „${user.displayName}“ zurücksetzen?\n\n${RESET_CONSEQUENCE}`)
    ) {
      return close()
    }
    void run(async () => {
      const result = await resetUserPassword(user.id)
      if (result.setupUrl) {
        onSetupLink({
          user,
          url: result.setupUrl,
          deliveryPath: result.deliveryPath,
          kind: 'RESET',
        })
        return `Für „${user.displayName}“ liegt ein Rücksetzlink zur Übergabe bereit.`
      }
      return `Der Rücksetzlink wurde an ${user.email} versendet.`
    }, 'Das Zurücksetzen ist fehlgeschlagen.')
  }

  function generatePassword() {
    if (
      !window.confirm(
        `Passwort für „${user.displayName}“ erzeugen?\n\n${GENERATE_PASSWORD_CONSEQUENCE}`,
      )
    ) {
      return close()
    }
    void run(async () => {
      const result = await generateUserPassword(user.id)
      onGeneratedPassword(user, result.password)
      return `Für „${user.displayName}“ wurde ein Passwort erzeugt.`
    }, 'Das Passwort konnte nicht erzeugt werden.')
  }

  function remove() {
    if (!window.confirm(`„${user.displayName}“ löschen?\n\n${DELETE_CONSEQUENCE}`)) return close()
    void run(async () => {
      await deleteUser(user.id)
      return `„${user.displayName}“ wurde gelöscht.`
    }, 'Das Konto konnte nicht gelöscht werden.')
  }

  const lockDisabled = isSelf
  const deleteDisabled = isSelf || user.bootstrap
  const deleteTooltip = isSelf
    ? SELF_ACTION_TOOLTIP
    : user.bootstrap
      ? BOOTSTRAP_DELETE_TOOLTIP
      : ''

  return (
    <>
      <Tooltip title="Weitere Aktionen">
        <span>
          <IconButton
            size="small"
            aria-label={`Aktionen für „${user.displayName}“`}
            aria-haspopup="true"
            disabled={busy}
            onClick={(e) => setAnchor(e.currentTarget)}
          >
            <MoreVertIcon fontSize="small" />
          </IconButton>
        </span>
      </Tooltip>
      <Menu
        anchorEl={anchor}
        open={anchor !== null}
        onClose={close}
        slotProps={{ list: { 'aria-label': `Aktionen für „${user.displayName}“` } }}
      >
        <MenuItem
          onClick={() => {
            close()
            onEdit(user)
          }}
        >
          <ListItemIcon>
            <EditOutlinedIcon fontSize="small" />
          </ListItemIcon>
          Bearbeiten
        </MenuItem>
        {user.status === 'LOCKED' ? (
          <MenuItem onClick={unlock}>
            <ListItemIcon>
              <LockOpenOutlinedIcon fontSize="small" />
            </ListItemIcon>
            Entsperren
          </MenuItem>
        ) : (
          <MenuItem
            onClick={lock}
            disabled={lockDisabled}
            // Kein <span> um den Eintrag: Ein Zwischenelement im `menu` verletzt
            // `aria-required-children` (Review-Runde 1). Die Begründung hängt deshalb als
            // `title` am Eintrag selbst und steht zusätzlich als Text darunter.
            title={lockDisabled ? SELF_ACTION_TOOLTIP : undefined}
          >
            <ListItemIcon>
              <LockOutlinedIcon fontSize="small" />
            </ListItemIcon>
            Sperren
          </MenuItem>
        )}
        <MenuItem onClick={resetPassword}>
          <ListItemIcon>
            <MailOutlinedIcon fontSize="small" />
          </ListItemIcon>
          Rücksetz-Link per E-Mail
        </MenuItem>
        <MenuItem onClick={generatePassword}>
          <ListItemIcon>
            <KeyOutlinedIcon fontSize="small" />
          </ListItemIcon>
          Passwort erzeugen
        </MenuItem>
        <Divider />
        <MenuItem onClick={remove} disabled={deleteDisabled} title={deleteTooltip || undefined}>
          <ListItemIcon>
            <DeleteOutlineIcon fontSize="small" color={deleteDisabled ? undefined : 'error'} />
          </ListItemIcon>
          <Typography sx={{ fontSize: 14, color: deleteDisabled ? undefined : 'error.main' }}>
            Löschen
          </Typography>
        </MenuItem>
        {(lockDisabled || deleteDisabled) && (
          <Typography sx={{ fontSize: 11.5, color: 'text.secondary', px: 2, py: 1, maxWidth: 320 }}>
            {isSelf ? SELF_ACTION_TOOLTIP : BOOTSTRAP_DELETE_TOOLTIP}
          </Typography>
        )}
      </Menu>
    </>
  )
}
