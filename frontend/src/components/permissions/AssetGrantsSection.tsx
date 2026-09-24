import { useEffect, useState, type ReactNode } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
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
import PageSection from '../PageSection'
import SectionHead from '../SectionHead'
import type {
  AssetGrantResponse,
  AssetGrantSubjectType,
  AssetRole,
  AssetType,
} from '../../types/api'
import { useAuthStore } from '../../stores/authStore'
import { successionAwareMessage } from '../succession/successionConflict'
import { confirmAction } from '../../stores/confirmStore'
import { assetKey, useGrantStore } from '../../stores/grantStore'
import GroupMembersDisclosure from './GroupMembersDisclosure'
import SubjectPicker from './SubjectPicker'
import {
  confirmAllAccountsSubject,
  confirmExternalSubject,
  confirmResolvedGroupById,
  emptySubjectSelection,
  selectedSubjectId,
  type SubjectSelection,
} from './subjectSelection'
import { getGrantedGroupMembers, resolveSelectableGroup } from '../../services/api'
import {
  allAccountsLabel,
  assetGrantScopeHint,
  assetRoleDescription,
  assetRoleLabel,
  assetTypeLabel,
  groupGrowthLabel,
  permissionSubjectTypeLabel,
} from '../../utils/labels'

const grantableRoles: AssetRole[] = ['VIEWER', 'EDITOR', 'MANAGER', 'OWNER']

/**
 * Was „Alle Konten" höchstens halten darf (#1931, ADR-0037 Entscheidung 1): Verwaltung und
 * Eigentum sind Zuständigkeiten und bleiben an eine benannte Person oder Gruppe gebunden. Das
 * Backend weist mehr mit 400 ab; hier steht es gar nicht erst zur Wahl.
 */
const allAccountsRoles: AssetRole[] = ['VIEWER', 'EDITOR']

// RFC 4122-shaped, version-agnostic - loose enough for any UUID the backend hands out (v4 grant
// subjects, v7-or-whatever future ids) while still catching the typo/paste-error case the client
// can check without a round trip (#423 code review, nit 2).
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

function rolesFor(subjectType: AssetGrantSubjectType): AssetRole[] {
  return subjectType === 'ALL_ACCOUNTS' ? allAccountsRoles : grantableRoles
}

function isValidUuid(value: string): boolean {
  return UUID_PATTERN.test(value.trim())
}

export interface AssetGrantsSectionProps {
  assetType: AssetType
  assetId: string
  /** Die Obergrenze der Systemverwaltung, unter der Liste — nur wo es eine gibt. */
  capControl?: ReactNode
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

// subjectDisplayName/grantedByDisplayName come resolved from the backend (AssetGrantService), which
// is what a MANAGER without the admin user list can see; the raw id is the fallback for a subject
// the backend itself could no longer resolve. A protected group stays nameless (ADR-0036,
// Entscheidung 9), and "Alle Konten" names no row at all (#1931).
function subjectDisplayName(grant: AssetGrantResponse): string {
  if (grant.protectedGroup) return 'Geschützte Gruppe'
  return grant.subjectDisplayName ?? grant.subjectId ?? allAccountsLabel
}

function grantedByDisplayName(grant: AssetGrantResponse): string {
  if (!grant.grantedByUserId) return '—'
  return grant.grantedByDisplayName ?? grant.grantedByUserId
}

/**
 * Die Berechtigungen eines Assets als Abschnitt der Seite (#1941) — Liste, Rollenwechsel, Entzug
 * und das Formular zum Freigeben, ohne Dialog davor. Der Abschnitt kennt vom Typ nur sein Wort;
 * was ein Typ darüber hinaus hat, kommt als Schacht herein.
 */
export default function AssetGrantsSection({
  assetType,
  assetId,
  capControl,
}: AssetGrantsSectionProps) {
  const currentUserId = useAuthStore((s) => s.user?.id)
  const grants = useGrantStore((s) => s.grantsByAsset[assetKey(assetType, assetId)]) ?? []
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
    void loadGrants(assetType, assetId)
  }, [assetType, assetId, loadGrants])

  function resetForm() {
    setShowForm(false)
    setSubject(emptySubjectSelection)
    setManualIdEntry(false)
    setManualId('')
    setRole('VIEWER')
    setExpiryInput('')
    setFormError(null)
  }

  async function handleRoleChange(grant: AssetGrantResponse, newRole: AssetRole) {
    setRowError(null)
    try {
      await upsertExistingGrant(assetType, assetId, {
        subjectType: grant.subjectType,
        subjectId: grant.subjectId,
        role: newRole,
        expiresAt: grant.expiresAt ?? null,
      })
    } catch (err) {
      setRowError(successionAwareMessage(err, 'Rolle konnte nicht geändert werden'))
    }
  }

  async function handleRevoke(grant: AssetGrantResponse) {
    // Revoking one's own grant is backend-permitted (only the last-active-OWNER guard can block
    // it) and can lock the caller out of this very section, which the generic "cannot be undone"
    // wording does not say.
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
      await revokeExistingGrant(assetType, assetId, grant.id)
    } catch (err) {
      setRowError(err instanceof Error ? err.message : 'Freigabe konnte nicht entzogen werden')
    }
  }

  async function handleSubmit() {
    setFormError(null)
    // #1931: "Alle Konten" names no row - it is the one recipient that carries no id, and
    // the only one that asks back before it is granted.
    if (subject.type === 'ALL_ACCOUNTS') {
      if (expiryInput && isDateInThePast(expiryInput)) {
        setFormError('Das Ablaufdatum darf nicht in der Vergangenheit liegen')
        return
      }
      if (!(await confirmAllAccountsSubject(assetRoleLabel(role)))) return
      setSubmitting(true)
      try {
        await upsertExistingGrant(assetType, assetId, {
          subjectType: 'ALL_ACCOUNTS',
          role,
          expiresAt: toExpiresAt(expiryInput),
        })
        resetForm()
      } catch (err) {
        setFormError(successionAwareMessage(err, 'Freigabe konnte nicht erteilt werden'))
      } finally {
        setSubmitting(false)
      }
      return
    }
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
      await upsertExistingGrant(assetType, assetId, {
        subjectType: subject.type,
        subjectId,
        role,
        expiresAt: toExpiresAt(expiryInput),
      })
      resetForm()
    } catch (err) {
      setFormError(successionAwareMessage(err, 'Freigabe konnte nicht erteilt werden'))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <PageSection
      title="Berechtigungen"
      description={`${assetGrantScopeHint(assetType)} Der Empfänger muss ihr nicht zustimmen. Eine Freigabe an „${allAccountsLabel}" ist jederzeit wieder zurücknehmbar — auch die.`}
    >
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
          Es sind noch keine Freigaben für diese {assetTypeLabel(assetType)} erteilt.
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
                  {/* #1880, ADR-0036 Entscheidung 9: Wer einer Gruppe hier ein Recht eingeräumt
                      hat, sieht, an wen — erst auf ausdrücklichen Wunsch, nie als Beiwerk dieser
                      Liste. Eine abgelaufene Freigabe hält nichts mehr; der Dienst antwortet
                      dann wie auf eine unbekannte Gruppe. */}
                  {grant.subjectType === 'GROUP' && grant.subjectId && !expired && (
                    <GroupMembersDisclosure
                      key={grant.subjectId}
                      groupLabel={grant.subjectDisplayName ?? subjectName}
                      load={(offset, limit) =>
                        getGrantedGroupMembers(
                          assetType,
                          assetId,
                          grant.subjectId as string,
                          offset,
                          limit,
                        )
                      }
                    />
                  )}
                </Stack>
                <FormControl size="small" sx={{ minWidth: 160 }}>
                  <InputLabel id={roleSelectId}>Rolle</InputLabel>
                  <Select
                    // Every row visually shows the same "Rolle" label, but a screen reader needs to
                    // tell rows apart. Deliberately no labelId here (only the standalone InputLabel
                    // above for the visual notch) - the ARIA accname algorithm resolves
                    // aria-labelledby before aria-label, so keeping labelId would silently override
                    // the aria-label below back to the shared "Rolle".
                    label="Rolle"
                    value={grant.role}
                    aria-label={`Rolle für ${subjectName}`}
                    onChange={(e) => void handleRoleChange(grant, e.target.value as AssetRole)}
                  >
                    {rolesFor(grant.subjectType).map((option) => (
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
            allowAllAccounts
            value={subject}
            onChange={(next) => {
              // Eine getippte Kennung gehört zu genau einer Art von Empfänger: Wer von Gruppe
              // auf Person umstellt, hätte sonst dieselbe UUID unter „Nutzer-ID" stehen.
              if (next.type !== subject.type) setManualId('')
              // Der Deckel für „Alle Konten" gilt auch für eine vorher gewählte Rolle.
              if (!rolesFor(next.type).includes(role)) setRole('VIEWER')
              setSubject(next)
            }}
            hideSearch={manualIdEntry}
          />

          {manualIdEntry && subject.type !== 'ALL_ACCOUNTS' && (
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
              {rolesFor(subject.type).map((option) => (
                <MenuItem key={option} value={option}>
                  {assetRoleLabel(option)}
                </MenuItem>
              ))}
            </Select>
          </FormControl>
          <Typography variant="caption" sx={{ color: 'text.secondary' }}>
            {assetRoleDescription(role, assetType)}
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

      {capControl}

      <Divider sx={{ my: 2 }} />
      <SectionHead component="h3">Rollen</SectionHead>
      <Stack spacing={0.5}>
        {grantableRoles.map((option) => (
          <Typography key={option} sx={{ fontSize: 12.5, color: 'text.secondary' }}>
            <strong>{assetRoleLabel(option)}</strong> · {assetRoleDescription(option, assetType)}
          </Typography>
        ))}
      </Stack>
    </PageSection>
  )
}
