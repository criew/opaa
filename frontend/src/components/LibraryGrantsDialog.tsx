import { useEffect, useMemo, useState } from 'react'
import Alert from '@mui/material/Alert'
import Autocomplete from '@mui/material/Autocomplete'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Divider from '@mui/material/Divider'
import FormControl from '@mui/material/FormControl'
import FormControlLabel from '@mui/material/FormControlLabel'
import FormLabel from '@mui/material/FormLabel'
import IconButton from '@mui/material/IconButton'
import InputLabel from '@mui/material/InputLabel'
import MenuItem from '@mui/material/MenuItem'
import Radio from '@mui/material/Radio'
import RadioGroup from '@mui/material/RadioGroup'
import Select from '@mui/material/Select'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import AddIcon from '@mui/icons-material/Add'
import DeleteIcon from '@mui/icons-material/Delete'
import type {
  AssetGrantResponse,
  AssetRole,
  GroupListResponse,
  PermissionSubjectType,
  UserInfo,
} from '../types/api'
import { getGroups, getMyGroups, getUsers } from '../services/api'
import { useAuthStore } from '../stores/authStore'
import { useGrantStore } from '../stores/grantStore'
import { assetRoleDescription, assetRoleLabel, permissionSubjectTypeLabel } from '../utils/labels'

const grantableRoles: AssetRole[] = ['VIEWER', 'EDITOR', 'MANAGER', 'OWNER']

interface LibraryGrantsDialogProps {
  open: boolean
  library: { id: string; name: string }
  onClose: () => void
}

function formatExpiry(expiresAt: string | null | undefined): string {
  if (!expiresAt) return 'unbefristet'
  return `bis ${new Date(expiresAt).toLocaleDateString('de-DE')}`
}

function isExpired(expiresAt: string | null | undefined): boolean {
  return !!expiresAt && new Date(expiresAt).getTime() < Date.now()
}

// Deliberately without a trailing "Z": interpreted in the caller's local timezone so the
// resulting instant stays end-of-day on the selected calendar date for that caller, instead of
// end-of-day UTC rolling into the next local date for anyone east of Greenwich.
function toExpiresAt(dateInput: string): string | null {
  if (!dateInput) return null
  return new Date(`${dateInput}T23:59:59.999`).toISOString()
}

function isDateInThePast(dateInput: string): boolean {
  return new Date(`${dateInput}T23:59:59.999`).getTime() < Date.now()
}

export default function LibraryGrantsDialog({ open, library, onClose }: LibraryGrantsDialogProps) {
  const isSystemAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')
  const grants = useGrantStore((s) => s.grantsByLibrary[library.id]) ?? []
  const isLoading = useGrantStore((s) => s.isLoading)
  const loadError = useGrantStore((s) => s.error)
  const loadGrants = useGrantStore((s) => s.loadGrants)
  const upsertExistingGrant = useGrantStore((s) => s.upsertExistingGrant)
  const revokeExistingGrant = useGrantStore((s) => s.revokeExistingGrant)

  const [groups, setGroups] = useState<GroupListResponse[]>([])
  const [groupsError, setGroupsError] = useState<string | null>(null)
  const [users, setUsers] = useState<UserInfo[]>([])
  // Set on a failed GET /v1/admin/users - expected for a MANAGER without a system role, since that
  // endpoint is admin-restricted (see #423's "technische Hinweise"). The person picker then falls
  // back to a free-text user id field instead of hiding grant creation entirely; #445 tracks a
  // proper permission-independent user search to replace this fallback.
  const [usersUnavailable, setUsersUnavailable] = useState(false)

  const [showForm, setShowForm] = useState(false)
  const [subjectType, setSubjectType] = useState<PermissionSubjectType>('USER')
  const [selectedUser, setSelectedUser] = useState<UserInfo | null>(null)
  const [manualUserId, setManualUserId] = useState('')
  const [selectedGroup, setSelectedGroup] = useState<GroupListResponse | null>(null)
  const [role, setRole] = useState<AssetRole>('VIEWER')
  const [expiryInput, setExpiryInput] = useState('')
  const [formError, setFormError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [rowError, setRowError] = useState<string | null>(null)

  useEffect(() => {
    if (!open) return
    void loadGrants(library.id)
    void (isSystemAdmin ? getGroups() : getMyGroups())
      .then((result) => {
        setGroups(result)
        setGroupsError(null)
      })
      .catch((err) => {
        setGroups([])
        setGroupsError(err instanceof Error ? err.message : 'Gruppen konnten nicht geladen werden')
      })
    void getUsers()
      .then((result) => {
        setUsers(result)
        setUsersUnavailable(false)
      })
      .catch(() => {
        setUsers([])
        setUsersUnavailable(true)
      })
  }, [open, library.id, isSystemAdmin, loadGrants])

  const groupsById = useMemo(
    () => Object.fromEntries(groups.map((group) => [group.id, group.name])),
    [groups],
  )
  const usersById = useMemo(
    () => Object.fromEntries(users.map((user) => [user.id, user.displayName ?? user.email])),
    [users],
  )

  function subjectDisplayName(grant: AssetGrantResponse): string {
    if (grant.subjectType === 'GROUP') {
      return groupsById[grant.subjectId] ?? grant.subjectId
    }
    return usersById[grant.subjectId] ?? grant.subjectId
  }

  function grantedByDisplayName(grant: AssetGrantResponse): string {
    if (!grant.grantedByUserId) return '—'
    return usersById[grant.grantedByUserId] ?? grant.grantedByUserId
  }

  function resetForm() {
    setShowForm(false)
    setSubjectType('USER')
    setSelectedUser(null)
    setManualUserId('')
    setSelectedGroup(null)
    setRole('VIEWER')
    setExpiryInput('')
    setFormError(null)
  }

  function handleClose() {
    if (submitting) return
    resetForm()
    setRowError(null)
    onClose()
  }

  async function handleRoleChange(grant: AssetGrantResponse, newRole: AssetRole) {
    setRowError(null)
    try {
      await upsertExistingGrant(library.id, {
        subjectType: grant.subjectType,
        subjectId: grant.subjectId,
        role: newRole,
        expiresAt: grant.expiresAt ?? null,
      })
    } catch (err) {
      setRowError(err instanceof Error ? err.message : 'Rolle konnte nicht geändert werden')
    }
  }

  async function handleRevoke(grant: AssetGrantResponse) {
    if (
      !window.confirm(
        `Freigabe für "${subjectDisplayName(grant)}" entziehen? Diese Aktion kann nicht rückgängig gemacht werden.`,
      )
    ) {
      return
    }
    setRowError(null)
    try {
      await revokeExistingGrant(library.id, grant.id)
    } catch (err) {
      setRowError(err instanceof Error ? err.message : 'Freigabe konnte nicht entzogen werden')
    }
  }

  async function handleSubmit() {
    setFormError(null)
    const subjectId: string | null =
      subjectType === 'GROUP'
        ? (selectedGroup?.id ?? null)
        : usersUnavailable
          ? manualUserId.trim()
          : (selectedUser?.id ?? null)
    if (!subjectId) {
      setFormError(
        subjectType === 'GROUP' ? 'Bitte eine Gruppe auswählen' : 'Bitte eine Person auswählen',
      )
      return
    }
    if (expiryInput && isDateInThePast(expiryInput)) {
      setFormError('Das Ablaufdatum darf nicht in der Vergangenheit liegen')
      return
    }
    setSubmitting(true)
    try {
      await upsertExistingGrant(library.id, {
        subjectType,
        subjectId,
        role,
        expiresAt: toExpiresAt(expiryInput),
      })
      resetForm()
    } catch (err) {
      setFormError(err instanceof Error ? err.message : 'Freigabe konnte nicht erteilt werden')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Dialog open={open} onClose={handleClose} maxWidth="md" fullWidth>
      <DialogTitle>Rechte · {library.name}</DialogTitle>
      <DialogContent>
        <Alert severity="info" sx={{ mb: 2 }}>
          Eine Freigabe gewährt Zugriff auf alle Dokumente dieser Bibliothek, nicht auf eine
          Auswahl. Der Empfänger muss ihr nicht zustimmen.
        </Alert>

        {loadError && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {loadError}
          </Alert>
        )}
        {rowError && (
          <Alert severity="error" sx={{ mb: 2 }} onClose={() => setRowError(null)}>
            {rowError}
          </Alert>
        )}

        {isLoading ? (
          <Typography color="text.secondary">Berechtigungen werden geladen …</Typography>
        ) : grants.length === 0 ? (
          <Typography color="text.secondary">
            Es sind noch keine Freigaben für diese Bibliothek erteilt.
          </Typography>
        ) : (
          <Stack spacing={1} sx={{ mb: 2 }}>
            {grants.map((grant) => {
              const expired = isExpired(grant.expiresAt)
              const roleSelectId = `grant-role-${grant.id}`
              return (
                <Box
                  key={grant.id}
                  sx={{
                    display: 'flex',
                    alignItems: 'center',
                    flexWrap: 'wrap',
                    gap: 1,
                    p: 1.5,
                    border: '1px solid',
                    borderColor: 'divider',
                    borderRadius: 1,
                  }}
                >
                  <Stack spacing={0.25} sx={{ minWidth: 160, flexGrow: 1 }}>
                    <Typography sx={{ fontWeight: 600 }}>{subjectDisplayName(grant)}</Typography>
                    <Typography variant="caption" color="text.secondary">
                      {permissionSubjectTypeLabel(grant.subjectType)} · erteilt von{' '}
                      {grantedByDisplayName(grant)} am{' '}
                      {new Date(grant.createdAt).toLocaleDateString('de-DE')}
                    </Typography>
                  </Stack>
                  <FormControl size="small" sx={{ minWidth: 160 }}>
                    <InputLabel id={roleSelectId}>Rolle</InputLabel>
                    <Select
                      labelId={roleSelectId}
                      label="Rolle"
                      value={grant.role}
                      onChange={(e) => void handleRoleChange(grant, e.target.value as AssetRole)}
                    >
                      {grantableRoles.map((option) => (
                        <MenuItem key={option} value={option}>
                          {assetRoleLabel(option)}
                        </MenuItem>
                      ))}
                    </Select>
                  </FormControl>
                  <Chip
                    label={formatExpiry(grant.expiresAt)}
                    size="small"
                    variant="outlined"
                    color={expired ? 'warning' : 'default'}
                  />
                  {expired && <Chip label="abgelaufen" size="small" color="warning" />}
                  <IconButton
                    aria-label={`Freigabe für ${subjectDisplayName(grant)} entziehen`}
                    size="small"
                    onClick={() => void handleRevoke(grant)}
                  >
                    <DeleteIcon fontSize="small" />
                  </IconButton>
                </Box>
              )
            })}
          </Stack>
        )}

        <Divider sx={{ mb: 2 }} />

        {!showForm ? (
          <Button startIcon={<AddIcon />} onClick={() => setShowForm(true)}>
            Freigeben
          </Button>
        ) : (
          <Stack spacing={2} sx={{ mt: 1 }}>
            <Typography variant="subtitle2">Freigeben</Typography>
            {formError && <Alert severity="error">{formError}</Alert>}

            <FormControl>
              <FormLabel id="grant-subject-type-label">Subjekt</FormLabel>
              <RadioGroup
                row
                aria-labelledby="grant-subject-type-label"
                value={subjectType}
                onChange={(e) => setSubjectType(e.target.value as PermissionSubjectType)}
              >
                <FormControlLabel value="USER" control={<Radio />} label="Person" />
                <FormControlLabel value="GROUP" control={<Radio />} label="Gruppe" />
              </RadioGroup>
            </FormControl>

            {subjectType === 'USER' &&
              (usersUnavailable ? (
                <Stack spacing={0.5}>
                  <TextField
                    label="Nutzer-ID"
                    placeholder="UUID des Nutzers"
                    value={manualUserId}
                    onChange={(e) => setManualUserId(e.target.value)}
                    size="small"
                  />
                  <Typography variant="caption" color="text.secondary">
                    Eine Nutzerauswahl steht ohne Systemrolle nicht zur Verfügung; die Nutzer-ID
                    muss bekannt sein. Nachgemeldet als Folge-Issue für eine
                    berechtigungsunabhängige Nutzersuche.
                  </Typography>
                </Stack>
              ) : (
                <Autocomplete
                  options={users}
                  getOptionLabel={(option) =>
                    option.displayName
                      ? `${option.displayName} (${option.email ?? option.id})`
                      : (option.email ?? option.id)
                  }
                  value={selectedUser}
                  onChange={(_event, value) => setSelectedUser(value)}
                  isOptionEqualToValue={(option, value) => option.id === value.id}
                  renderInput={(params) => (
                    <TextField {...params} label="Person auswählen" placeholder="Person suchen …" />
                  )}
                  size="small"
                />
              ))}

            {subjectType === 'GROUP' && (
              <Stack spacing={0.5}>
                {groupsError && <Alert severity="error">{groupsError}</Alert>}
                {groups.length === 0 && !groupsError && (
                  <Alert severity="info">
                    Es sind keine Gruppen verfügbar, denen eine Freigabe erteilt werden kann.
                  </Alert>
                )}
                <Autocomplete
                  options={groups}
                  getOptionLabel={(option) => option.name}
                  value={selectedGroup}
                  onChange={(_event, value) => setSelectedGroup(value)}
                  isOptionEqualToValue={(option, value) => option.id === value.id}
                  disabled={groups.length === 0}
                  renderInput={(params) => (
                    <TextField
                      {...params}
                      label="Gruppe auswählen"
                      placeholder="Gruppe auswählen …"
                    />
                  )}
                  size="small"
                />
              </Stack>
            )}

            <FormControl size="small">
              <InputLabel id="grant-role-label">Rolle</InputLabel>
              <Select
                labelId="grant-role-label"
                label="Rolle"
                value={role}
                onChange={(e) => setRole(e.target.value as AssetRole)}
              >
                {grantableRoles.map((option) => (
                  <MenuItem key={option} value={option}>
                    {assetRoleLabel(option)}
                  </MenuItem>
                ))}
              </Select>
            </FormControl>
            <Typography variant="caption" color="text.secondary">
              {assetRoleDescription(role)}
            </Typography>

            <TextField
              label="Befristung (optional)"
              type="date"
              value={expiryInput}
              onChange={(e) => setExpiryInput(e.target.value)}
              slotProps={{ inputLabel: { shrink: true } }}
              size="small"
              sx={{ maxWidth: 220 }}
            />

            <Stack direction="row" spacing={1}>
              <Button variant="contained" onClick={() => void handleSubmit()} disabled={submitting}>
                {submitting ? 'Wird erteilt …' : 'Freigeben'}
              </Button>
              <Button onClick={resetForm} disabled={submitting}>
                Abbrechen
              </Button>
            </Stack>
          </Stack>
        )}

        <Divider sx={{ my: 2 }} />
        <Typography variant="subtitle2" gutterBottom>
          Rollen
        </Typography>
        <Stack spacing={0.5}>
          {grantableRoles.map((option) => (
            <Typography key={option} variant="body2" color="text.secondary">
              <strong>{assetRoleLabel(option)}</strong> · {assetRoleDescription(option)}
            </Typography>
          ))}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={handleClose}>Schließen</Button>
      </DialogActions>
    </Dialog>
  )
}
