import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import MenuItem from '@mui/material/MenuItem'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import type { AccountResponse, SystemRole } from '@/shared/api/types'
import { notify } from '@/shared/stores/notificationStore'
import { useRecentLoginGate } from '@/shared/session/useRecentLoginGate'
import { useUserAdminStore } from '../stores/userAdminStore'
import { accountOriginLabel } from './accountLabels'
import {
  PRIVILEGED_CONFIRM_PURPOSE,
  SYSTEM_ROLES,
  SYSTEM_ROLE_LABEL,
  localUserErrorMessage,
} from './localUserLabels'

interface RoleChangeDialogProps {
  /** The account whose role is being changed; `null` keeps the dialog closed. */
  account: AccountResponse | null
  onClose: () => void
}

/**
 * Change an account's role - for provider accounts the one path, for local ones it is
 * also in the edit dialog. The role endpoint is the same for both (ADR-0033, Entscheidung
 * 11); its two rejections - provider-managed role, last login-capable system administrator -
 * appear here as a sentence with the next step instead of a status code. A change demands a recent
 * sign-in: its refusal opens the confirmation, after which the same change is sent again.
 */
export default function RoleChangeDialog({ account, onClose }: RoleChangeDialogProps) {
  const changeRole = useUserAdminStore((s) => s.changeRole)
  const [role, setRole] = useState<SystemRole | null>(null)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const gate = useRecentLoginGate(PRIVILEGED_CONFIRM_PURPOSE)
  const open = account !== null
  const selected = role ?? account?.systemRole ?? 'USER'
  const name = account?.displayName ?? account?.email ?? ''

  async function save(afterConfirmation = false) {
    if (!account || selected === account.systemRole) return
    setSaving(true)
    setError(null)
    try {
      await changeRole(account.id, selected)
      gate.settled()
      notify(`Die Rolle von „${name}“ ist jetzt ${SYSTEM_ROLE_LABEL[selected]}.`, 'success')
      onClose()
    } catch (err) {
      const refused = gate.refusal(err, () => void save(true), afterConfirmation)
      if (refused === null) {
        setError(localUserErrorMessage(err, 'Die Rolle konnte nicht geändert werden.'))
      } else if (refused) {
        setError(refused)
      }
    } finally {
      setSaving(false)
    }
  }

  return (
    <>
      <Dialog
        open={open}
        onClose={saving ? undefined : onClose}
        aria-labelledby="role-change-title"
        maxWidth="xs"
        fullWidth
      >
        <DialogTitle id="role-change-title">Rolle von „{name}“ ändern</DialogTitle>
        <DialogContent>
          {account && (
            <Typography sx={{ fontSize: 13, color: 'text.secondary', mb: 2 }}>
              Konto bei {accountOriginLabel(account)}
              {account.email ? ` · ${account.email}` : ''}. Die Rolle gilt für die ganze Anwendung;
              Rechte an Räumen und Bibliotheken werden dort vergeben.
            </Typography>
          )}
          {error && (
            <Alert severity="error" sx={{ mb: 2 }}>
              {error}
            </Alert>
          )}
          <Box>
            <Typography
              id="role-change-role-label"
              component="span"
              sx={{ display: 'block', fontSize: 12.5, fontWeight: 500, mb: 0.5 }}
            >
              Rolle
            </Typography>
            <TextField
              id="role-change-role"
              select
              fullWidth
              size="small"
              // MUI's select renders a display with role="combobox" that needs its name via
              // aria-labelledby (axe aria-input-field-name) - pattern as in UserFormDialog.
              slotProps={{
                select: { SelectDisplayProps: { 'aria-labelledby': 'role-change-role-label' } },
              }}
              value={selected}
              onChange={(e) => setRole(e.target.value as SystemRole)}
              disabled={saving}
            >
              {SYSTEM_ROLES.map((entry) => (
                <MenuItem key={entry} value={entry}>
                  {SYSTEM_ROLE_LABEL[entry]}
                </MenuItem>
              ))}
            </TextField>
          </Box>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose} disabled={saving}>
            Abbrechen
          </Button>
          <Button
            variant="contained"
            onClick={() => void save()}
            disabled={saving || !account || selected === account.systemRole}
          >
            Speichern
          </Button>
        </DialogActions>
      </Dialog>
      {gate.dialog}
    </>
  )
}
