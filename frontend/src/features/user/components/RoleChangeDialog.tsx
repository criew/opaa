import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import MenuItem from '@mui/material/MenuItem'
import FormField from '@/shared/ui/form/FormField'
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
import { fontSize } from '@/shared/theme/tokens'
import FormDialog from '@/shared/ui/dialog/FormDialog'

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
    if (!account) return
    // nothing changed: there is nothing to save, the dialog just closes
    if (selected === account.systemRole) {
      onClose()
      return
    }
    setSaving(true)
    setError(null)
    try {
      const updated = await changeRole(account.id, selected)
      gate.settled()
      notify(
        updated.systemRole === selected
          ? `Die Rolle von „${name}“ ist jetzt ${SYSTEM_ROLE_LABEL[selected]}.`
          : `Die Rolle ${SYSTEM_ROLE_LABEL[selected]} für „${name}“ ist beantragt; sie wird wirksam, sobald die Revision bestätigt.`,
        'success',
      )
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
      <FormDialog
        open={open}
        titleId="role-change-title"
        title={`Rolle von „${name}“ ändern`}
        submitLabel="Speichern"
        onSubmit={() => void save()}
        onClose={onClose}
        busy={saving}
        busyAnnouncement="Die Rolle wird gespeichert"
      >
        {account && (
          <Typography sx={{ fontSize: fontSize.text, color: 'text.secondary', mb: 2 }}>
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
          <FormField
            id="role-change-role"
            label="Rolle"
            select
            fullWidth
            size="small"
            value={selected}
            onChange={(e) => setRole(e.target.value as SystemRole)}
            disabled={saving}
          >
            {SYSTEM_ROLES.map((entry) => (
              <MenuItem key={entry} value={entry}>
                {SYSTEM_ROLE_LABEL[entry]}
              </MenuItem>
            ))}
          </FormField>
        </Box>
        {selected === 'AUDITOR' && account?.systemRole !== 'AUDITOR' && (
          <Alert severity="info" sx={{ mt: 2 }}>
            Die Rolle {SYSTEM_ROLE_LABEL.AUDITOR} vergibt die Systemverwaltung nicht allein: Sie
            wird beantragt und erst wirksam, wenn eine Person der Revision bestätigt. Ein lokales
            Konto verliert dabei sein Passwort; den Link für ein neues erhält die bestätigende
            Person der Revision und übergibt ihn – nicht die Systemverwaltung und nicht per E-Mail.
          </Alert>
        )}
      </FormDialog>
      {gate.dialog}
    </>
  )
}
