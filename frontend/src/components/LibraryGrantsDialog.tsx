import { useEffect, useState } from 'react'
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
import Link from '@mui/material/Link'
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

// RFC 4122-shaped, version-agnostic - loose enough for any UUID the backend hands out (v4 grant
// subjects, v7-or-whatever future ids) while still catching the typo/paste-error case the client
// can check without a round trip (#423 code review, nit 2).
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

function isValidUuid(value: string): boolean {
  return UUID_PATTERN.test(value.trim())
}

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

// #423 code review, finding 1: subjectDisplayName/grantedByDisplayName now come resolved from the
// backend (AssetGrantService#toResponses) instead of being looked up here via GET /v1/admin/users,
// which is SYSTEM_ADMIN-only and left every name blank for the MANAGER the issue is built for.
// Falls back to the raw id only if the backend itself could not resolve it (a deleted subject).
function subjectDisplayName(grant: AssetGrantResponse): string {
  return grant.subjectDisplayName ?? grant.subjectId
}

function grantedByDisplayName(grant: AssetGrantResponse): string {
  if (!grant.grantedByUserId) return '—'
  return grant.grantedByDisplayName ?? grant.grantedByUserId
}

export default function LibraryGrantsDialog({ open, library, onClose }: LibraryGrantsDialogProps) {
  const currentUserId = useAuthStore((s) => s.user?.id)
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
  // GET /v1/admin/users is SYSTEM_ADMIN-only, so it is only ever attempted as a convenience for an
  // admin picking a person - never as the source of the names shown on existing grants (see
  // subjectDisplayName/grantedByDisplayName above, which read the backend-resolved fields
  // instead). A MANAGER without a system role goes straight to the free-text id field below; #445
  // tracks a proper permission-independent user search to replace it.
  const usersUnavailable = !isSystemAdmin || users.length === 0

  const [showForm, setShowForm] = useState(false)
  const [subjectType, setSubjectType] = useState<PermissionSubjectType>('USER')
  const [selectedUser, setSelectedUser] = useState<UserInfo | null>(null)
  const [manualUserId, setManualUserId] = useState('')
  const [selectedGroup, setSelectedGroup] = useState<GroupListResponse | null>(null)
  // #423 code review, finding 3: GET /v1/me/groups only ever returns the caller's own memberships,
  // but AssetGrantService#requireGrantableGroup accepts any group in the organization - a MANAGER
  // of a library can legitimately share it with a group they do not belong to. The picker above
  // stays as a convenience for the common case; this lets a caller name a group by id regardless.
  const [manualGroupEntry, setManualGroupEntry] = useState(false)
  const [manualGroupId, setManualGroupId] = useState('')
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
    // No else branch resetting `users` for a non-admin: usersUnavailable below already forces the
    // free-text fallback via `!isSystemAdmin` alone, so a stale `users` array from an earlier
    // admin session (if any) is never rendered regardless of its contents.
    if (isSystemAdmin) {
      void getUsers()
        .then((result) => setUsers(result))
        .catch(() => setUsers([]))
    }
  }, [open, library.id, isSystemAdmin, loadGrants])

  function resetForm() {
    setShowForm(false)
    setSubjectType('USER')
    setSelectedUser(null)
    setManualUserId('')
    setSelectedGroup(null)
    setManualGroupEntry(false)
    setManualGroupId('')
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
    // #423 code review, nit 3: revoking one's own grant is backend-permitted (only the
    // last-active-OWNER guard can block it) and has an easy-to-miss consequence - it can lock the
    // caller out of this very dialog, which the generic "cannot be undone" wording does not say.
    const isSelf = grant.subjectType === 'USER' && grant.subjectId === currentUserId
    const question = isSelf
      ? `Freigabe für "${subjectDisplayName(grant)}" entziehen? Das ist Ihre eigene Freigabe - Sie verlieren dadurch möglicherweise selbst den Zugriff auf diese Rechteansicht. Diese Aktion kann nicht rückgängig gemacht werden.`
      : `Freigabe für "${subjectDisplayName(grant)}" entziehen? Diese Aktion kann nicht rückgängig gemacht werden.`
    if (!window.confirm(question)) {
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
        ? manualGroupEntry
          ? manualGroupId.trim()
          : (selectedGroup?.id ?? null)
        : usersUnavailable
          ? manualUserId.trim()
          : (selectedUser?.id ?? null)
    if (!subjectId) {
      setFormError(
        subjectType === 'GROUP' ? 'Bitte eine Gruppe auswählen' : 'Bitte eine Person auswählen',
      )
      return
    }
    const isManualEntry = subjectType === 'GROUP' ? manualGroupEntry : usersUnavailable
    if (isManualEntry && !isValidUuid(subjectId)) {
      setFormError(
        subjectType === 'GROUP'
          ? 'Die Gruppen-ID muss eine gültige UUID sein'
          : 'Die Nutzer-ID muss eine gültige UUID sein',
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
              const subjectName = subjectDisplayName(grant)
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
                    <Typography sx={{ fontWeight: 600 }}>{subjectName}</Typography>
                    <Typography variant="caption" color="text.secondary">
                      {permissionSubjectTypeLabel(grant.subjectType)} · erteilt von{' '}
                      {grantedByDisplayName(grant)} am{' '}
                      {new Date(grant.createdAt).toLocaleDateString('de-DE')}
                    </Typography>
                  </Stack>
                  <FormControl size="small" sx={{ minWidth: 160 }}>
                    <InputLabel id={roleSelectId}>Rolle</InputLabel>
                    <Select
                      // #423 code review, nit 5: every row visually shows the same "Rolle" label,
                      // but a screen reader needs to tell rows apart when several grants are
                      // listed. Deliberately no labelId here (only the standalone InputLabel above
                      // for the visual notch) - the ARIA accname algorithm resolves
                      // aria-labelledby before aria-label, so keeping labelId would silently
                      // override the aria-label below back to the shared "Rolle".
                      label="Rolle"
                      value={grant.role}
                      aria-label={`Rolle für ${subjectName}`}
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
                    aria-label={`Freigabe für ${subjectName} entziehen`}
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
                {groups.length === 0 && !groupsError && !manualGroupEntry && (
                  <Alert severity="info">
                    Es sind keine Gruppen verfügbar, denen eine Freigabe erteilt werden kann.
                  </Alert>
                )}
                {manualGroupEntry ? (
                  <TextField
                    label="Gruppen-ID"
                    placeholder="UUID der Gruppe"
                    value={manualGroupId}
                    onChange={(e) => setManualGroupId(e.target.value)}
                    size="small"
                  />
                ) : (
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
                )}
                <Typography variant="caption" color="text.secondary">
                  {manualGroupEntry
                    ? 'Jede Gruppe der eigenen Organisation ist zulässig, auch ohne eigene Mitgliedschaft.'
                    : 'Diese Auswahl zeigt nur Gruppen, in denen Sie selbst Mitglied sind.'}{' '}
                  <Link
                    component="button"
                    type="button"
                    onClick={() => {
                      setManualGroupEntry((current) => !current)
                      setSelectedGroup(null)
                      setManualGroupId('')
                    }}
                  >
                    {manualGroupEntry
                      ? 'Stattdessen aus Liste wählen'
                      : 'Andere Gruppen-ID eingeben'}
                  </Link>
                </Typography>
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
