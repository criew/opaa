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
} from '../../../types/api'
import { apiFieldErrors } from '../../../services/apiErrorDetails'
import { useUserAdminStore } from '../../../stores/userAdminStore'
import FieldLabel from '../../wizard/FieldLabel'
import {
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
 * Anlegen und Bearbeiten eines lokalen Kontos (#1541, ADR-0033 Entscheidung 11). Der Anlagegrund
 * ist Pflicht und zweckgebunden – sein Hilfetext nennt, was nicht hineingehört; das Ablaufdatum ist
 * vorbelegt und nur ausdrücklich abwählbar, damit ein unbefristetes Konto eine Entscheidung bleibt
 * und kein Versehen.
 *
 * Die Feldfehler des Backends (`fieldErrors`) landen an ihrem Feld, die Konfliktcodes als Meldung
 * über dem Formular: `EMAIL_TAKEN` betrifft ein Feld, `LAST_LOGIN_CAPABLE_ADMIN` das Ganze.
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

  const isEdit = user !== undefined
  const isValid =
    draft.displayName.trim() !== '' &&
    draft.createdReason.trim() !== '' &&
    (isEdit || draft.email.trim() !== '') &&
    (draft.noExpiry || draft.expiresAt !== '')

  function update(patch: Partial<UserDraft>) {
    setDraft((current) => ({ ...current, ...patch }))
  }

  async function submit() {
    setError(null)
    setFieldErrors({})
    setSubmitting(true)
    try {
      if (isEdit) {
        /*
         * Das Ablaufdatum reist nur mit, wenn der **Tag** sich geändert hat (Review-Runde 1,
         * HIGH 2): Ein unverändertes Feld würde sonst auf 23:59:59 Ortszeit zurückgerechnet und
         * damit das gespeicherte Datum verschieben - und weil `LOCAL_USER_CHANGED` Vorher/Nachher
         * ausschließlich für `expires_at` führt, stünde eine reine Namensänderung als
         * Fristverschiebung im Protokoll.
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
        onUpdated(await updateUser(user.id, request))
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
        onCreated(await createUser(request))
      }
    } catch (err) {
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
    <Dialog open={open} fullWidth maxWidth="sm" onClose={onClose} aria-labelledby="user-form-title">
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
              // Ein <label for> benennt nur echte Formularelemente; MUIs Auswahl rendert eine
              // Anzeige mit role="combobox", die ihren Namen über aria-labelledby braucht (axe
              // aria-input-field-name, serious). Muster wie in LibraryGrantsDialog.
              slotProps={{
                select: { SelectDisplayProps: { 'aria-labelledby': 'user-form-role-label' } },
              }}
              value={draft.systemRole}
              onChange={(e) => update({ systemRole: e.target.value as SystemRole })}
              error={Boolean(fieldErrors.systemRole)}
              helperText={fieldErrors.systemRole}
            >
              {SYSTEM_ROLES.map((role) => (
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
              disabled={draft.noExpiry}
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
                  onChange={(e) => update({ noExpiry: e.target.checked })}
                />
              }
              label="Kein Ablaufdatum"
            />
            {draft.noExpiry && (
              <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
                {NO_EXPIRY_HELP}
              </Typography>
            )}
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
        <Button variant="contained" onClick={() => void submit()} disabled={!isValid || submitting}>
          {isEdit ? 'Speichern' : 'Anlegen'}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
