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
import { useUserAdminStore } from '../stores/userAdminStore'
import { accountOriginLabel } from './accountLabels'
import { SYSTEM_ROLES, SYSTEM_ROLE_LABEL, localUserErrorMessage } from './localUserLabels'

interface RoleChangeDialogProps {
  /** The account whose role is being changed; `null` keeps the dialog closed. */
  account: AccountResponse | null
  onClose: () => void
}

/**
 * Change an account's role - for provider accounts the one path, for local ones it is
 * also in the edit dialog. The role endpoint is the same for both (ADR-0033, Entscheidung
 * 11); its two rejections - provider-managed role, last login-capable system administrator -
 * appear here as a sentence with the next step instead of a status code.
 */
export default function RoleChangeDialog({ account, onClose }: RoleChangeDialogProps) {
  const changeRole = useUserAdminStore((s) => s.changeRole)
  const [role, setRole] = useState<SystemRole | null>(null)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const open = account !== null
  const selected = role ?? account?.systemRole ?? 'USER'
  const name = account?.displayName ?? account?.email ?? ''

  async function save() {
    if (!account || selected === account.systemRole) return
    setSaving(true)
    setError(null)
    try {
      const updated = await changeRole(account.id, selected)
      notify(
        updated.systemRole === selected
          ? `Die Rolle von „${name}“ ist jetzt ${SYSTEM_ROLE_LABEL[selected]}.`
          : `Die Rolle ${SYSTEM_ROLE_LABEL[selected]} für „${name}“ ist beantragt; sie wird wirksam, sobald die Revision bestätigt.`,
        'success',
      )
      onClose()
    } catch (err) {
      setError(localUserErrorMessage(err, 'Die Rolle konnte nicht geändert werden.'))
    } finally {
      setSaving(false)
    }
  }

  return (
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
        {selected === 'AUDITOR' && account?.systemRole !== 'AUDITOR' && (
          <Alert severity="info" sx={{ mt: 2 }}>
            Die Rolle {SYSTEM_ROLE_LABEL.AUDITOR} vergibt die Systemverwaltung nicht allein: Sie
            wird beantragt und erst wirksam, wenn eine Person der Revision bestätigt. Ein lokales
            Konto verliert dabei sein Passwort; die Person setzt ein neues über einen Link an ihre
            Adresse.
          </Alert>
        )}
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
  )
}
