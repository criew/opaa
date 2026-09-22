import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Divider from '@mui/material/Divider'
import FormControl from '@mui/material/FormControl'
import IconButton from '@mui/material/IconButton'
import InputLabel from '@mui/material/InputLabel'
import Link from '@mui/material/Link'
import MenuItem from '@mui/material/MenuItem'
import Select from '@mui/material/Select'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import AddIcon from '@mui/icons-material/Add'
import DeleteIcon from '@mui/icons-material/Delete'
import SectionHead from './SectionHead'
import LibraryExternalAccessSection from './library/LibraryExternalAccessSection'
import type { AssetGrantResponse, AssetRole } from '../types/api'
import { useAuthStore } from '../stores/authStore'
import { confirmAction } from '../stores/confirmStore'
import { useGrantStore } from '../stores/grantStore'
import SubjectPicker from './permissions/SubjectPicker'
import {
  confirmExternalSubject,
  confirmResolvedGroupById,
  emptySubjectSelection,
  selectedSubjectId,
  type SubjectSelection,
} from './permissions/subjectSelection'
import { resolveSelectableGroup } from '../services/api'
import {
  assetRoleDescription,
  assetRoleLabel,
  groupGrowthLabel,
  permissionSubjectTypeLabel,
} from '../utils/labels'

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
//
// #1820: Eine geschützte Gruppe hat hier keinen Namen - der Dienst liefert keinen, und die Zeile
// bleibt trotzdem, damit die Freigabe entzogen werden kann (ADR-0036, Entscheidung 9).
function subjectDisplayName(grant: AssetGrantResponse): string {
  if (grant.protectedGroup) return 'Geschützte Gruppe'
  return grant.subjectDisplayName ?? grant.subjectId
}

function grantedByDisplayName(grant: AssetGrantResponse): string {
  if (!grant.grantedByUserId) return '—'
  return grant.grantedByDisplayName ?? grant.grantedByUserId
}

export default function LibraryGrantsDialog({ open, library, onClose }: LibraryGrantsDialogProps) {
  const currentUserId = useAuthStore((s) => s.user?.id)
  const grants = useGrantStore((s) => s.grantsByLibrary[library.id]) ?? []
  const isLoading = useGrantStore((s) => s.isLoading)
  const loadError = useGrantStore((s) => s.error)
  const loadGrants = useGrantStore((s) => s.loadGrants)
  const upsertExistingGrant = useGrantStore((s) => s.upsertExistingGrant)
  const revokeExistingGrant = useGrantStore((s) => s.revokeExistingGrant)

  const [showForm, setShowForm] = useState(false)
  const [subject, setSubject] = useState<SubjectSelection>(emptySubjectSelection)
  // #1820: Die Eingabe per Kennung bleibt der Rückfall, wenn die Suche nichts hergibt - für eine
  // Gruppe unterliegt sie derselben Durchsetzung wie die Suche: eine nicht freigegebene Gruppe
  // antwortet auch hier mit „nicht gefunden" (ADR-0036, Entscheidung 9).
  const [manualIdEntry, setManualIdEntry] = useState(false)
  const [manualId, setManualId] = useState('')
  const [role, setRole] = useState<AssetRole>('VIEWER')
  const [expiryInput, setExpiryInput] = useState('')
  const [formError, setFormError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [rowError, setRowError] = useState<string | null>(null)

  useEffect(() => {
    if (!open) return
    void loadGrants(library.id)
  }, [open, library.id, loadGrants])

  function resetForm() {
    setShowForm(false)
    setSubject(emptySubjectSelection)
    setManualIdEntry(false)
    setManualId('')
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
    const confirmed = await confirmAction({
      question: `Freigabe für "${subjectDisplayName(grant)}" entziehen?`,
      consequence: isSelf
        ? 'Das ist Ihre eigene Freigabe - Sie verlieren dadurch möglicherweise selbst den Zugriff auf diese Rechteansicht. Diese Aktion kann nicht rückgängig gemacht werden.'
        : 'Diese Aktion kann nicht rückgängig gemacht werden.',
      confirmLabel: 'Entziehen',
      tone: 'danger',
    })
    if (!confirmed) {
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
    const subjectId: string | null = manualIdEntry ? manualId.trim() : selectedSubjectId(subject)
    if (!subjectId) {
      setFormError(
        subject.type === 'GROUP' ? 'Bitte eine Gruppe auswählen' : 'Bitte eine Person auswählen',
      )
      return
    }
    if (manualIdEntry && !isValidUuid(subjectId)) {
      setFormError(
        subject.type === 'GROUP'
          ? 'Die Gruppen-ID muss eine gültige UUID sein'
          : 'Die Nutzer-ID muss eine gültige UUID sein',
      )
      return
    }
    if (expiryInput && isDateInThePast(expiryInput)) {
      setFormError('Das Ablaufdatum darf nicht in der Vergangenheit liegen')
      return
    }
    // #1820: Der Kennungsweg loest die Gruppe auf, bevor irgendetwas erteilt wird - sonst fehlte
    // genau dort die Zwischenfrage fuer eine Gruppe eines externen Anbieters, wo Herkunft und
    // Symbol ohnehin nicht zu sehen sind (ADR-0036, Entscheidung 2). Was sich fuer diesen
    // Aufrufer nicht aufloesen laesst, wird nicht erteilt.
    if (manualIdEntry && subject.type === 'GROUP') {
      setSubmitting(true)
      let resolved
      try {
        resolved = await resolveSelectableGroup(subjectId)
      } catch (err) {
        setSubmitting(false)
        setFormError(err instanceof Error ? err.message : 'Gruppe nicht gefunden')
        return
      }
      setSubmitting(false)
      if (!(await confirmResolvedGroupById(resolved))) return
    } else if (!(await confirmExternalSubject(subject))) {
      return
    }
    setSubmitting(true)
    try {
      await upsertExistingGrant(library.id, {
        subjectType: subject.type,
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
          <Typography sx={{ color: 'text.secondary' }}>Berechtigungen werden geladen …</Typography>
        ) : grants.length === 0 ? (
          <Typography sx={{ color: 'text.secondary' }}>
            Es sind noch keine Freigaben für diese Bibliothek erteilt.
          </Typography>
        ) : (
          <Stack spacing={1} sx={{ mb: 2 }}>
            {grants.map((grant) => {
              const expired = isExpired(grant.expiresAt)
              const subjectName = subjectDisplayName(grant)
              // #1820, ADR-0036 Entscheidung 9: „Referat 50: 23 bei Erteilung, heute 41" - eine
              // Zeile, die jemand liest, der für die Freigabe geradesteht.
              const growthHint =
                grant.subjectType === 'GROUP' ? groupGrowthLabel(grant, 'Erteilung') : null
              const roleSelectId = `grant-role-${grant.id}`
              return (
                <Box
                  key={grant.id}
                  sx={{
                    display: 'flex',
                    alignItems: 'center',
                    flexWrap: 'wrap',
                    gap: 1,
                    py: 1.25,
                    // Der Dialog bringt seine Fläche mit; die Einträge darin sind Zeilen mit einem
                    // Trenner unten (#1608, Regel 2), keine Kästen im Kasten.
                    borderBottom: 1,
                    borderColor: 'divider',
                    '&:last-of-type': { borderBottom: 0 },
                  }}
                >
                  <Stack spacing={0.25} sx={{ minWidth: 160, flexGrow: 1 }}>
                    <Typography sx={{ fontSize: 13.5, fontWeight: 600 }}>{subjectName}</Typography>
                    {/* grantedByUserId names whoever conferred the role the grant carries now, not
                        whoever created the row - so it is never paired with createdAt, which would
                        assert a granter/date combination that never existed. */}
                    <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                      {permissionSubjectTypeLabel(grant.subjectType)} · Rolle vergeben von{' '}
                      {grantedByDisplayName(grant)} · zuletzt geändert am{' '}
                      {new Date(grant.updatedAt).toLocaleDateString('de-DE')}
                      {growthHint ? ` · ${growthHint}` : ''}
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
            <SectionHead component="h3">Freigeben</SectionHead>
            {formError && <Alert severity="error">{formError}</Alert>}

            <SubjectPicker
              labelId="grant-subject-type-label"
              value={subject}
              onChange={(next) => {
                // Eine getippte Kennung gehört zu genau einer Art von Empfänger: Wer von Gruppe
                // auf Person umstellt, hätte sonst dieselbe UUID unter „Nutzer-ID" stehen.
                if (next.type !== subject.type) setManualId('')
                setSubject(next)
              }}
              hideSearch={manualIdEntry}
            />

            {manualIdEntry && (
              <TextField
                label={subject.type === 'GROUP' ? 'Gruppen-ID' : 'Nutzer-ID'}
                placeholder={subject.type === 'GROUP' ? 'UUID der Gruppe' : 'UUID des Nutzers'}
                value={manualId}
                onChange={(e) => setManualId(e.target.value)}
                size="small"
              />
            )}

            <Typography variant="caption" sx={{ color: 'text.secondary' }}>
              {manualIdEntry
                ? 'Für die Eingabe per Kennung gilt dieselbe Regel wie für die Suche: Was Ihnen die Suche nicht zeigt, ist auch hier nicht zu finden.'
                : subject.type === 'GROUP'
                  ? 'Angezeigt werden Anbietergruppen und interne Gruppen, die ihre Verantwortlichen zur Verwendung freigegeben haben.'
                  : 'Gesucht wird in Ihrer Organisation.'}{' '}
              <Link
                component="button"
                type="button"
                onClick={() => {
                  setManualIdEntry((current) => !current)
                  setSubject({ ...subject, user: null, group: null })
                  setManualId('')
                }}
              >
                {manualIdEntry
                  ? 'Stattdessen suchen'
                  : subject.type === 'GROUP'
                    ? 'Gruppen-ID eingeben'
                    : 'Nutzer-ID eingeben'}
              </Link>
            </Typography>

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
            <Typography variant="caption" sx={{ color: 'text.secondary' }}>
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

        <LibraryExternalAccessSection libraryId={library.id} />

        <Divider sx={{ my: 2 }} />
        <SectionHead component="h3">Rollen</SectionHead>
        <Stack spacing={0.5}>
          {grantableRoles.map((option) => (
            <Typography key={option} sx={{ fontSize: 12.5, color: 'text.secondary' }}>
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
