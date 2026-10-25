import { useEffect, useState, type ReactNode } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import FormControl from '@mui/material/FormControl'
import FormControlLabel from '@mui/material/FormControlLabel'
import InputLabel from '@mui/material/InputLabel'
import Link from '@mui/material/Link'
import MenuItem from '@mui/material/MenuItem'
import Radio from '@mui/material/Radio'
import RadioGroup from '@mui/material/RadioGroup'
import Select from '@mui/material/Select'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import PageSection from '@/shared/ui/PageSection'
import { usePlainSections } from '@/shared/ui/plainSections'
import type {
  AssetGrantResponse,
  AssetGrantSubjectType,
  AssetRole,
  AssetType,
} from '@/shared/api/types'
import { useAuthStore } from '@/shared/session/authStore'
import { successionAwareMessage } from '@/features/succession'
import { confirmAction } from '@/shared/stores/confirmStore'
import { assetKey, useGrantStore } from '../stores/grantStore'
import { GroupMembersDisclosure } from '@/features/principal'
import { SubjectPicker } from '@/features/principal'
import {
  confirmAllAccountsSubject,
  confirmExternalSubject,
  confirmResolvedGroupById,
  emptySubjectSelection,
  selectedSubjectId,
  type SubjectSelection,
} from '@/features/principal'
import { getGrantedGroupMembers } from '../api/assetApi'
import { resolveSelectableGroup } from '@/features/principal'
import { allAccountsLabel, assetRoleLabel } from '@/shared/utils/labels'
import {
  assetGrantScopeHint,
  assetRoleDescription,
  groupMemberCountLabel,
  permissionSubjectTypeLabel,
} from './assetLabels'
import { assetTypeLabel } from '../../../shared/assetTypes'

const grantableRoles: AssetRole[] = ['VIEWER', 'EDITOR', 'MANAGER', 'OWNER']

/**
 * The most „Alle Konten" may hold (ADR-0037 Entscheidung 1): management and
 * ownership are responsibilities and stay bound to a named person or group. The
 * backend rejects more with 400; here it is not even offered.
 */
const allAccountsRoles: AssetRole[] = ['VIEWER', 'EDITOR']

// RFC 4122-shaped, version-agnostic - loose enough for any UUID the backend hands out (v4 grant
// subjects, v7-or-whatever future ids) while still catching the typo/paste-error case the client
// can check without a round trip.
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
  /** The system administration's upper bound, below the list — only where there is one. */
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
// Entscheidung 9), and "Alle Konten" names no row at all.
function subjectDisplayName(grant: AssetGrantResponse): string {
  if (grant.protectedGroup) return 'Geschützte Gruppe'
  return grant.subjectDisplayName ?? grant.subjectId ?? allAccountsLabel
}

function grantedByDisplayName(grant: AssetGrantResponse): string {
  if (!grant.grantedByUserId) return '—'
  return grant.grantedByDisplayName ?? grant.grantedByUserId
}

/**
 * The permissions of an asset as a section of the page — list, role change, revocation
 * and the form for sharing, without a dialog in front. The section knows only the type's word;
 * whatever a type has beyond that comes in as a slot.
 */
/** From this many grants on, the list gets a search (pruefliste-detail D19). */
const SEARCH_FROM = 8

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
  const [query, setQuery] = useState('')
  const [rolesOpen, setRolesOpen] = useState(false)
  const plain = usePlainSections()
  const normalizedQuery = query.trim().toLowerCase()
  // in a detail tab the owner has a row of its own; its OWNER grant is not listed again (P50)
  const listedGrants = plain ? grants.filter((grant) => grant.role !== 'OWNER') : grants
  const shownGrants = normalizedQuery
    ? listedGrants.filter((grant) =>
        subjectDisplayName(grant).toLowerCase().includes(normalizedQuery),
      )
    : listedGrants
  const [subject, setSubject] = useState<SubjectSelection>(emptySubjectSelection)
  // Entry by identifier stays the fallback when the search yields nothing - for a
  // group it is subject to the same enforcement as the search: a group that is not shared
  // answers „nicht gefunden" here as well (ADR-0036, Entscheidung 9).
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
    // ADR-0037 Entscheidung 9: the follow-up question depends on the reach, not on the form.
    // Whoever moves the existing row „Alle Konten" from „Lesen" to „Bearbeiten" enlarges it
    // just as when granting - and is asked just the same.
    if (
      grant.subjectType === 'ALL_ACCOUNTS' &&
      !(await confirmAllAccountsSubject(assetRoleLabel(newRole)))
    ) {
      return
    }
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
    // "Alle Konten" names no row - it is the one recipient that carries no id, and
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
    // The identifier path resolves the group before anything is granted - otherwise the
    // confirmation question for a group of an external provider would be missing exactly where
    // origin and symbol are not visible anyway (ADR-0036, Entscheidung 2). Whatever cannot be
    // resolved for this caller is not granted.
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
      description={
        plain
          ? undefined
          : `${assetGrantScopeHint(assetType)} Der Empfänger muss ihr nicht zustimmen. Eine Freigabe an „${allAccountsLabel}" ist jederzeit wieder zurücknehmbar — auch die.`
      }
      action={
        !showForm ? (
          <Link
            component="button"
            type="button"
            underline="hover"
            onClick={() => setShowForm(true)}
            sx={{ fontSize: 13.5, fontWeight: 500 }}
          >
            Freigeben
          </Link>
        ) : undefined
      }
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

      {showForm && (
        <Stack spacing={2} sx={{ mb: 3, pb: 3, borderBottom: 1, borderColor: 'divider' }}>
          {formError && <Alert severity="error">{formError}</Alert>}

          {manualIdEntry ? (
            <>
              <FormControl>
                <Typography
                  id="grant-manual-id-kind-label"
                  component="span"
                  sx={{ fontSize: 13, fontWeight: 600 }}
                >
                  Art der Kennung
                </Typography>
                <RadioGroup
                  row
                  aria-labelledby="grant-manual-id-kind-label"
                  value={subject.type}
                  onChange={(event) => {
                    // A typed id belongs to exactly one kind of recipient.
                    setManualId('')
                    setSubject({
                      type: event.target.value as AssetGrantSubjectType,
                      user: null,
                      group: null,
                    })
                  }}
                >
                  <FormControlLabel value="USER" control={<Radio />} label="Person" />
                  <FormControlLabel value="GROUP" control={<Radio />} label="Gruppe" />
                </RadioGroup>
              </FormControl>
              <TextField
                label={subject.type === 'GROUP' ? 'Gruppen-ID' : 'Nutzer-ID'}
                placeholder={subject.type === 'GROUP' ? 'UUID der Gruppe' : 'UUID des Nutzers'}
                value={manualId}
                onChange={(e) => setManualId(e.target.value)}
                size="small"
              />
            </>
          ) : (
            <SubjectPicker
              allowAllAccounts
              value={subject}
              onChange={(next) => {
                // The cap for „Alle Konten" applies to a previously chosen role as well.
                if (!rolesFor(next.type).includes(role)) setRole('VIEWER')
                setSubject(next)
              }}
            />
          )}

          <Typography variant="caption" sx={{ color: 'text.secondary' }}>
            {manualIdEntry
              ? 'Für die Eingabe per Kennung gilt dieselbe Regel wie für die Suche: Was Ihnen die Suche nicht zeigt, ist auch hier nicht zu finden.'
              : 'Gesucht wird in Ihrer Organisation. Gruppen erscheinen, wenn sie aus einem Anbieter stammen oder ihre Verantwortlichen sie zur Verwendung freigegeben haben.'}{' '}
            <Link
              component="button"
              type="button"
              onClick={() => {
                setManualIdEntry((current) => !current)
                setSubject(emptySubjectSelection)
                setManualId('')
              }}
            >
              {manualIdEntry ? 'Stattdessen suchen' : 'Kennung eingeben'}
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

      {isLoading ? (
        <Typography sx={{ color: 'text.secondary' }}>Berechtigungen werden geladen …</Typography>
      ) : grants.length === 0 ? (
        <Typography sx={{ color: 'text.secondary' }}>
          Es sind noch keine Freigaben für diese {assetTypeLabel(assetType)} erteilt.
        </Typography>
      ) : (
        <Box sx={{ mb: 2 }}>
          {grants.length >= SEARCH_FROM && (
            <TextField
              size="small"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="Name"
              slotProps={{ htmlInput: { 'aria-label': 'Freigaben durchsuchen' } }}
              fullWidth
              sx={{ mb: 1 }}
            />
          )}
          <Box
            aria-hidden
            sx={{
              display: { xs: 'none', sm: 'flex' },
              gap: 1,
              py: 0.5,
              borderBottom: 1,
              borderColor: 'divider',
              fontSize: 12,
              color: 'text.secondary',
            }}
          >
            <Box sx={{ flex: 1 }}>Empfänger</Box>
            <Box sx={{ width: 144 }}>Rolle</Box>
            <Box sx={{ width: 112 }}>Befristung</Box>
            <Box sx={{ width: 64 }} />
          </Box>
          {shownGrants.map((grant) => {
            const expired = isExpired(grant.expiresAt)
            const subjectName = subjectDisplayName(grant)
            const sizeHint = grant.subjectType === 'GROUP' ? groupMemberCountLabel(grant) : null
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
                }}
              >
                <Stack
                  spacing={0.25}
                  sx={{ minWidth: 0, flex: { xs: '1 1 100%', sm: '1 1 160px' } }}
                >
                  <Typography noWrap title={subjectName} sx={{ fontSize: 13.5, fontWeight: 600 }}>
                    {subjectName}
                  </Typography>
                  {/* grantedByUserId names whoever conferred the role the grant carries now, not
                      whoever created the row - so it is never paired with createdAt, which would
                      assert a granter/date combination that never existed. */}
                  <Box
                    title={`Rolle vergeben von ${grantedByDisplayName(grant)} am ${new Date(grant.updatedAt).toLocaleDateString('de-DE')}`}
                    sx={{ display: 'flex', flexWrap: 'wrap', columnGap: 1, alignItems: 'baseline' }}
                  >
                    {/* „Alle Konten“ needs no second line naming its kind again */}
                    {permissionSubjectTypeLabel(grant.subjectType) !== subjectName && (
                      <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                        {permissionSubjectTypeLabel(grant.subjectType)}
                        {sizeHint ? ` · ${sizeHint}` : ''}
                      </Typography>
                    )}
                    {/* ADR-0036 Entscheidung 9: whoever granted a group a right here
                       sees to whom — only on explicit request, never as a by-product of this
                       list. An expired grant holds nothing any more; the service then answers
                       as for an unknown group. */}
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
                  </Box>
                </Stack>
                <FormControl size="small" sx={{ width: 144 }} id={roleSelectId}>
                  <Select
                    // Every row visually shows the same "Rolle" label, but a screen reader needs to
                    // tell rows apart. Deliberately no labelId here (only the standalone InputLabel
                    // above for the visual notch) - the ARIA accname algorithm resolves
                    // aria-labelledby before aria-label, so keeping labelId would silently override
                    // the aria-label below back to the shared "Rolle".
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
                <Typography
                  sx={{
                    // below sm only a set date takes room, so role and action share one line
                    width: { xs: grant.expiresAt ? 'auto' : 0, sm: 112 },
                    overflow: 'hidden',
                    fontSize: 13,
                    color: expired ? 'warning.main' : 'text.secondary',
                  }}
                >
                  {/* the normal case „unbefristet“ is not repeated in every row */}
                  {expired ? 'abgelaufen' : grant.expiresAt ? formatExpiry(grant.expiresAt) : ''}
                </Typography>
                <Link
                  component="button"
                  type="button"
                  underline="hover"
                  aria-label={`Entziehen: Freigabe für ${subjectName}`}
                  onClick={() => void handleRevoke(grant)}
                  sx={{ fontSize: 13.5, fontWeight: 500, minHeight: 24, ml: 'auto' }}
                >
                  Entziehen
                </Link>
              </Box>
            )
          })}
          {shownGrants.length === 0 && (
            <Typography sx={{ fontSize: 13.5, color: 'text.secondary', py: 1 }}>
              {normalizedQuery
                ? 'Keine Freigabe entspricht dieser Suche.'
                : 'Noch keine Freigaben.'}
            </Typography>
          )}
        </Box>
      )}

      {capControl}

      <Link
        component="button"
        type="button"
        underline="hover"
        aria-expanded={rolesOpen}
        onClick={() => setRolesOpen((value) => !value)}
        sx={{ fontSize: 13.5, fontWeight: 500 }}
      >
        Was die Rollen dürfen
      </Link>
      {rolesOpen && (
        <Stack spacing={0.5} sx={{ mt: 1 }}>
          {grantableRoles.map((option) => (
            <Typography key={option} sx={{ fontSize: 12.5, color: 'text.secondary' }}>
              <strong>{assetRoleLabel(option)}</strong> · {assetRoleDescription(option, assetType)}
            </Typography>
          ))}
        </Stack>
      )}
    </PageSection>
  )
}
