import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import FormControl from '@mui/material/FormControl'
import FormControlLabel from '@mui/material/FormControlLabel'
import FormLabel from '@mui/material/FormLabel'
import MenuItem from '@mui/material/MenuItem'
import Radio from '@mui/material/Radio'
import RadioGroup from '@mui/material/RadioGroup'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import type {
  LocalUserCreateRequest,
  LocalUserCreatedResponse,
  LocalUserCreationMode,
  LocalUserResponse,
  LocalUserUpdateRequest,
  SystemRole,
} from '@/shared/api/types'
import { apiFieldErrors } from '@/shared/api/apiErrorDetails'
import { useRecentLoginGate } from '@/shared/session/useRecentLoginGate'
import { useUserAdminStore } from '../stores/userAdminStore'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'
import LocalUserChatCleanupSection from './LocalUserChatCleanupSection'
import {
  PRIVILEGED_CONFIRM_PURPOSE,
  SYSTEM_ROLES,
  SYSTEM_ROLE_LABEL,
  defaultExpiryInputValue,
  todayInputValue,
  fromDateInputValue,
  localUserErrorMessage,
  toDateInputValue,
} from './localUserLabels'

export const CREATED_REASON_HELP =
  'Dienstlicher Anlass des Kontos und Grund der Befristung, höchstens 200 Zeichen. Die Person kann ' +
  'ihn in ihren Einstellungen lesen. Nicht hineingehören Angaben zu Gesundheit, ' +
  'Beschäftigungsverhältnis, Leistung, Disziplinarsachverhalten oder Dritten.'

export const NO_EXPIRY_HELP =
  'Ein Konto ohne Ablaufdatum ist eine ausdrückliche Entscheidung und erscheint im Hinweis zur ' +
  'Auflage, bis es befristet wird.'

/**
 * The emergency account does not count for the requirement check (ADR-0033, Entscheidung 11) – the
 * sentence above would otherwise promise it a hint in which it never appears.
 */
export const NO_EXPIRY_HELP_BOOTSTRAP =
  'Das Notanker-Konto der Systemverwaltung bleibt unbefristet; es erscheint nicht im Hinweis zur ' +
  'Auflage.'

/**
 * The emergency account accepts no expiry date and does not leave `SYSTEM_ADMIN` (ADR-0033,
 * Entscheidungen 3 and 5; otherwise 409 `BOOTSTRAP_ACCOUNT`). A date or another role from the
 * time before this lock can be withdrawn – that restores the promised state.
 */
export const BOOTSTRAP_ROLE_HINT =
  'Das Notanker-Konto behält die Rolle der Systemverwaltung – es ist der Weg zurück in eine ' +
  'Installation ohne funktionierenden Identitätsanbieter.'

const MODE_LABEL: Record<LocalUserCreationMode, string> = {
  INVITE: 'Einladung per E-Mail senden',
  INITIAL_PASSWORD: 'Anfangspasswort jetzt erzeugen (Wechsel bei der ersten Anmeldung)',
}

// Required fields carry aria-required but no asterisk (guidelines 5.2).
const REQUIRED_FIELD_SLOTS = { inputLabel: { required: false } } as const

interface UserDraft {
  email: string
  displayName: string
  systemRole: SystemRole
  expiresAt: string
  noExpiry: boolean
  createdReason: string
  mode: LocalUserCreationMode
}

function draftOf(user: LocalUserResponse | undefined, defaultExpiryDays: number): UserDraft {
  if (!user) {
    return {
      email: '',
      displayName: '',
      systemRole: 'USER',
      expiresAt: defaultExpiryInputValue(defaultExpiryDays),
      noExpiry: false,
      createdReason: '',
      mode: 'INVITE',
    }
  }
  return {
    email: user.email,
    displayName: user.displayName,
    systemRole: user.systemRole,
    expiresAt: toDateInputValue(user.expiresAt),
    noExpiry: !user.expiresAt,
    createdReason: user.createdReason,
    mode: 'INVITE',
  }
}

/** Neutral on purpose: no number, no hint whether the person connected anything. */
export const EXPIRY_CONSEQUENCE =
  'Mit dem Ablaufdatum enden etwaige verbundene Konten der Person, und für etwaige private ' +
  'Bibliotheken beginnt die Löschfrist.'

export const SELF_EXPIRY_HINT =
  'Das eigene Konto kann nicht rückwirkend ablaufen – sonst wäre die Verwaltung nach einem ' +
  'Fehlgriff nicht mehr erreichbar.'

interface UserFormDialogProps {
  open: boolean
  /** The account being edited; absent for creation. */
  user?: LocalUserResponse
  /** The caller's own account: its expiry date cannot move into the past (409 `SELF_LOCKOUT`). */
  isSelf?: boolean
  /** The prefill of a new account's expiry date, from the local auth settings. */
  defaultExpiryDays: number
  onClose: () => void
  onCreated: (created: LocalUserCreatedResponse) => void
  onUpdated: (updated: LocalUserResponse) => void
}

/**
 * Creating and editing a local account (ADR-0033 Entscheidung 11). The creation reason
 * is mandatory and purpose-bound – its help text names what does not belong in it; the expiry
 * date is prefilled and can only be deselected explicitly, so that an unlimited account stays a
 * decision and not an oversight.
 *
 * The backend's field errors (`fieldErrors`) land on their field, the conflict codes as a message
 * above the form: `EMAIL_TAKEN` concerns a field, `LAST_LOGIN_CAPABLE_ADMIN` the whole. A role
 * change demands a recent sign-in: its refusal opens the confirmation, after which the same form
 * is sent again.
 */
export default function UserFormDialog({
  open,
  user,
  isSelf = false,
  defaultExpiryDays,
  onClose,
  onCreated,
  onUpdated,
}: UserFormDialogProps) {
  const createUser = useUserAdminStore((s) => s.createUser)
  const updateUser = useUserAdminStore((s) => s.updateUser)

  // Seeded once per mount; the page remounts the dialog with a fresh key for every opening, so no
  // reopened dialog carries the previous draft or error along.
  const [draft, setDraft] = useState<UserDraft>(() => draftOf(user, defaultExpiryDays))
  const [error, setError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [submitting, setSubmitting] = useState(false)
  const gate = useRecentLoginGate(PRIVILEGED_CONFIRM_PURPOSE)

  const isEdit = user !== undefined
  const isBootstrap = user?.bootstrap === true
  const roleLocked = isBootstrap && user?.systemRole === 'SYSTEM_ADMIN'
  const roleOptions = isBootstrap
    ? SYSTEM_ROLES.filter((role) => role === 'SYSTEM_ADMIN' || role === user?.systemRole)
    : SYSTEM_ROLES
  const isValid =
    draft.displayName.trim() !== '' &&
    draft.createdReason.trim() !== '' &&
    (isEdit || draft.email.trim() !== '') &&
    (draft.noExpiry || draft.expiresAt !== '')

  function update(patch: Partial<UserDraft>) {
    setDraft((current) => ({ ...current, ...patch }))
  }

  async function submit(afterConfirmation = false) {
    setError(null)
    setFieldErrors({})
    setSubmitting(true)
    try {
      if (isEdit) {
        /*
         * The expiry date travels along only when the **day** has changed: an
         * unchanged field would otherwise be recalculated to 23:59:59 local time and
         * thus shift the stored date - and because `LOCAL_USER_CHANGED` records before/after
         * only for `expires_at`, a mere name change would appear as a
         * period shift in the log.
         */
        const storedExpiry = toDateInputValue(user.expiresAt)
        const expiryChanged = !draft.noExpiry && draft.expiresAt !== storedExpiry
        const expiryRemoved = draft.noExpiry && Boolean(user.expiresAt)
        const request: LocalUserUpdateRequest = {
          email: draft.email.trim(),
          displayName: draft.displayName.trim(),
          systemRole: draft.systemRole,
          createdReason: draft.createdReason.trim(),
          // noExpiry is the only way to remove a date; `false` plus an absent expiresAt means
          // „unchanged" (the flag itself is not optional in the request schema).
          noExpiry: expiryRemoved,
          ...(expiryChanged ? { expiresAt: fromDateInputValue(draft.expiresAt) } : {}),
        }
        const updated = await updateUser(user.id, request)
        gate.settled()
        onUpdated(updated)
      } else {
        const request: LocalUserCreateRequest = {
          email: draft.email.trim(),
          displayName: draft.displayName.trim(),
          systemRole: draft.systemRole,
          createdReason: draft.createdReason.trim(),
          noExpiry: draft.noExpiry,
          ...(draft.noExpiry ? {} : { expiresAt: fromDateInputValue(draft.expiresAt) }),
          mode: draft.mode,
        }
        const created = await createUser(request)
        gate.settled()
        onCreated(created)
      }
    } catch (err) {
      const refused = gate.refusal(err, () => void submit(true), afterConfirmation)
      if (refused !== null) {
        if (refused) setError(refused)
        return
      }
      const violations = apiFieldErrors(err)
      if (violations.length > 0) {
        setFieldErrors(Object.fromEntries(violations.map((v) => [v.field, v.message])))
      }
      setError(
        localUserErrorMessage(
          err,
          isEdit
            ? 'Die Änderungen konnten nicht gespeichert werden.'
            : 'Das Konto konnte nicht angelegt werden.',
        ),
      )
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <>
      <Dialog
        open={open}
        fullWidth
        maxWidth="sm"
        onClose={onClose}
        aria-labelledby="user-form-title"
      >
        <DialogTitle id="user-form-title">
          {isEdit ? `„${user.displayName}“ bearbeiten` : 'Lokales Konto anlegen'}
        </DialogTitle>
        <DialogContent>
          {error && (
            <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
              {error}
            </Alert>
          )}
          <Stack spacing={2}>
            <Box>
              <FieldLabel htmlFor="user-form-email">E-Mail-Adresse (Kennung)</FieldLabel>
              <TextField
                id="user-form-email"
                fullWidth
                size="small"
                type="email"
                required
                slotProps={REQUIRED_FIELD_SLOTS}
                value={draft.email}
                onChange={(e) => update({ email: e.target.value })}
                error={Boolean(fieldErrors.email)}
                helperText={fieldErrors.email}
              />
            </Box>
            <Box>
              <FieldLabel htmlFor="user-form-display-name">Anzeigename</FieldLabel>
              <TextField
                id="user-form-display-name"
                fullWidth
                size="small"
                required
                slotProps={REQUIRED_FIELD_SLOTS}
                value={draft.displayName}
                onChange={(e) => update({ displayName: e.target.value })}
                error={Boolean(fieldErrors.displayName)}
                helperText={fieldErrors.displayName}
              />
            </Box>
            <Box>
              <FieldLabel id="user-form-role-label" htmlFor="user-form-role">
                Rolle
              </FieldLabel>
              <TextField
                id="user-form-role"
                select
                fullWidth
                size="small"
                // A <label for> names only real form elements; MUI's select renders a
                // display with role="combobox" that needs its name via aria-labelledby (axe
                // aria-input-field-name, serious). Pattern as in AssetGrantsDialog.
                slotProps={{
                  select: { SelectDisplayProps: { 'aria-labelledby': 'user-form-role-label' } },
                }}
                value={draft.systemRole}
                disabled={roleLocked}
                onChange={(e) => update({ systemRole: e.target.value as SystemRole })}
                error={Boolean(fieldErrors.systemRole)}
                helperText={
                  fieldErrors.systemRole ?? (isBootstrap ? BOOTSTRAP_ROLE_HINT : undefined)
                }
              >
                {roleOptions.map((role) => (
                  <MenuItem key={role} value={role}>
                    {SYSTEM_ROLE_LABEL[role]}
                  </MenuItem>
                ))}
              </TextField>
            </Box>
            <Box>
              <FieldLabel htmlFor="user-form-expires-at">Ablaufdatum</FieldLabel>
              <TextField
                id="user-form-expires-at"
                fullWidth
                size="small"
                type="date"
                disabled={draft.noExpiry || isBootstrap}
                value={draft.expiresAt}
                onChange={(e) => update({ expiresAt: e.target.value })}
                error={Boolean(fieldErrors.expiresAt)}
                helperText={fieldErrors.expiresAt ?? (isSelf ? SELF_EXPIRY_HINT : undefined)}
                slotProps={isSelf ? { htmlInput: { min: todayInputValue() } } : undefined}
              />
              <FormControlLabel
                control={
                  <Checkbox
                    checked={draft.noExpiry}
                    disabled={isBootstrap && !user?.expiresAt}
                    onChange={(e) => update({ noExpiry: e.target.checked })}
                  />
                }
                label="Kein Ablaufdatum"
              />
              <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
                {draft.noExpiry
                  ? isBootstrap
                    ? NO_EXPIRY_HELP_BOOTSTRAP
                    : NO_EXPIRY_HELP
                  : EXPIRY_CONSEQUENCE}
              </Typography>
            </Box>
            <Box>
              <FieldLabel htmlFor="user-form-created-reason">Anlagegrund</FieldLabel>
              <TextField
                id="user-form-created-reason"
                fullWidth
                size="small"
                required
                multiline
                minRows={2}
                slotProps={{ ...REQUIRED_FIELD_SLOTS, htmlInput: { maxLength: 200 } }}
                value={draft.createdReason}
                onChange={(e) => update({ createdReason: e.target.value })}
                error={Boolean(fieldErrors.createdReason)}
                helperText={fieldErrors.createdReason ?? CREATED_REASON_HELP}
              />
            </Box>
            {isEdit && <LocalUserChatCleanupSection user={user} />}
            {!isEdit && (
              <FormControl>
                <FormLabel id="user-form-mode-label" sx={{ fontSize: 12, mb: '5px' }}>
                  Zugang einrichten
                </FormLabel>
                <RadioGroup
                  aria-labelledby="user-form-mode-label"
                  value={draft.mode}
                  onChange={(e) => update({ mode: e.target.value as LocalUserCreationMode })}
                >
                  {(Object.keys(MODE_LABEL) as LocalUserCreationMode[]).map((mode) => (
                    <FormControlLabel
                      key={mode}
                      value={mode}
                      control={<Radio size="small" />}
                      slotProps={{ typography: { sx: { fontSize: 13.5 } } }}
                      label={MODE_LABEL[mode]}
                    />
                  ))}
                </RadioGroup>
              </FormControl>
            )}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose} disabled={submitting}>
            Abbrechen
          </Button>
          <Button
            variant="contained"
            onClick={() => void submit()}
            disabled={!isValid || submitting}
          >
            {isEdit ? 'Speichern' : 'Anlegen'}
          </Button>
        </DialogActions>
      </Dialog>
      {gate.dialog}
    </>
  )
}
