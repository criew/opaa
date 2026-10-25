import { useEffect, useState, type ReactNode } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import FormControl from '@mui/material/FormControl'
import FormControlLabel from '@mui/material/FormControlLabel'
import IconButton from '@mui/material/IconButton'
import InputLabel from '@mui/material/InputLabel'
import Link from '@mui/material/Link'
import Menu from '@mui/material/Menu'
import MenuItem from '@mui/material/MenuItem'
import Radio from '@mui/material/Radio'
import RadioGroup from '@mui/material/RadioGroup'
import Select from '@mui/material/Select'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import MoreHorizIcon from '@mui/icons-material/MoreHoriz'
import TabSection, { EmptyLine, ListSearchField, TAB_CONTENT_WIDTH } from '@/shared/ui/TabSection'
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
  assetRoleDescription,
  groupMemberCountLabel,
  permissionSubjectTypeLabel,
} from './assetLabels'

const grantableRoles: AssetRole[] = ['VIEWER', 'EDITOR', 'MANAGER', 'OWNER']

/** From this many grants on, the list gets a search (D19) ... */
const SEARCH_FROM = 8
/** ... and from this many it is grouped by recipient kind. */
const GROUP_FROM = 50
const GROUP_ORDER: AssetGrantSubjectType[] = ['GROUP', 'USER', 'ALL_ACCOUNTS']
const GROUP_TITLES: Record<AssetGrantSubjectType, string> = {
  GROUP: 'Gruppen',
  USER: 'Personen',
  ALL_ACCOUNTS: allAccountsLabel,
}

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
  /**
   * The owner as the section „Eigentümer“ names it: its own grant is not listed again here, it
   * changes only through „Eigentum übergeben“.
   */
  ownerName?: string | null
}

function formatDay(value: string): string {
  return new Date(value).toLocaleDateString('de-DE', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
  })
}

/** A limit only where there is one; an open-ended grant says nothing about it. */
function formatExpiry(expiresAt: string | null | undefined): string | null {
  if (!expiresAt) return null
  return `bis ${formatDay(expiresAt)}`
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
export default function AssetGrantsSection({
  assetType,
  assetId,
  capControl,
  ownerName,
}: AssetGrantsSectionProps) {
  const currentUserId = useAuthStore((s) => s.user?.id)
  const allGrants = useGrantStore((s) => s.grantsByAsset[assetKey(assetType, assetId)]) ?? []
  const grants = allGrants.filter(
    (grant) => !(ownerName && grant.role === 'OWNER' && subjectDisplayName(grant) === ownerName),
  )
  const isLoading = useGrantStore((s) => s.isLoading)
  const loadError = useGrantStore((s) => s.error)
  const loadGrants = useGrantStore((s) => s.loadGrants)
  const upsertExistingGrant = useGrantStore((s) => s.upsertExistingGrant)
  const revokeExistingGrant = useGrantStore((s) => s.revokeExistingGrant)

  const [showForm, setShowForm] = useState(false)
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
  const [query, setQuery] = useState('')

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

  const grantForm = (
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
  )

  const needle = query.trim().toLocaleLowerCase('de-DE')
  const searchable = grants.length >= SEARCH_FROM
  const shownGrants = grants.filter(
    (grant) => !needle || subjectDisplayName(grant).toLocaleLowerCase('de-DE').includes(needle),
  )
  // A long list reads by recipient kind: groups first, then persons, then „Alle Konten".
  const groups: Array<{ title: string | null; grants: AssetGrantResponse[] }> =
    grants.length >= GROUP_FROM
      ? GROUP_ORDER.map((type) => ({
          title: GROUP_TITLES[type],
          grants: shownGrants.filter((grant) => grant.subjectType === type),
        })).filter((group) => group.grants.length > 0)
      : [{ title: null, grants: shownGrants }]

  function grantRow(grant: AssetGrantResponse) {
    return (
      <GrantRow
        key={grant.id}
        grant={grant}
        assetType={assetType}
        assetId={assetId}
        onRoleChange={(next) => void handleRoleChange(grant, next)}
        onRevoke={() => void handleRevoke(grant)}
      />
    )
  }

  return (
    <TabSection
      title="Berechtigungen"
      wide
      action={
        !showForm && !(grants.length === 0 && !isLoading) ? (
          <Button size="small" onClick={() => setShowForm(true)}>
            Freigeben
          </Button>
        ) : undefined
      }
      tools={
        searchable ? (
          <ListSearchField
            value={query}
            onChange={setQuery}
            placeholder="Person oder Gruppe suchen"
            ariaLabel="Freigaben durchsuchen"
          />
        ) : undefined
      }
    >
      <Box sx={{ maxWidth: TAB_CONTENT_WIDTH }}>
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

        {showForm && grantForm}

        {isLoading ? (
          <Typography sx={{ color: 'text.secondary' }}>Berechtigungen werden geladen …</Typography>
        ) : grants.length === 0 ? (
          !showForm && (
            <EmptyLine
              text="Noch keine Freigaben."
              action={
                <Button size="small" onClick={() => setShowForm(true)}>
                  Freigeben
                </Button>
              }
            />
          )
        ) : shownGrants.length === 0 ? (
          <Typography sx={{ color: 'text.secondary' }}>
            Keine Freigabe passt zu „{query.trim()}“.
          </Typography>
        ) : (
          <Box sx={{ mb: 2 }}>
            {groups.map((group) => (
              <Box key={group.title ?? 'all'} sx={{ mb: group.title ? 2 : 0 }}>
                {group.title && (
                  <Typography
                    component="h3"
                    sx={{ fontSize: 13.5, fontWeight: 600, color: 'text.secondary', mb: 0.5 }}
                  >
                    {group.title} · {group.grants.length}
                  </Typography>
                )}
                <Box component="ul" sx={{ m: 0, p: 0, listStyle: 'none' }}>
                  {group.grants.map(grantRow)}
                </Box>
              </Box>
            ))}
          </Box>
        )}

        {capControl}
      </Box>
    </TabSection>
  )
}

/**
 * One grant in two text lines - the name, then kind, members, limit and date - with the role
 * and „⋯“ at the shared right edge; below 600 px both move under the name in one line.
 */
function GrantRow({
  grant,
  assetType,
  assetId,
  onRoleChange,
  onRevoke,
}: {
  grant: AssetGrantResponse
  assetType: AssetType
  assetId: string
  onRoleChange: (role: AssetRole) => void
  onRevoke: () => void
}) {
  const [membersOpen, setMembersOpen] = useState(false)
  const [menuAnchor, setMenuAnchor] = useState<HTMLElement | null>(null)
  const expired = isExpired(grant.expiresAt)
  const subjectName = subjectDisplayName(grant)
  const members = grant.subjectType === 'GROUP' ? groupMemberCountLabel(grant) : null
  const expiry = formatExpiry(grant.expiresAt)
  // grantedByUserId names whoever conferred the role the grant carries now, so it is paired
  // with updatedAt (the last role change), never with createdAt.
  const since = `seit ${formatDay(grant.updatedAt)} von ${grantedByDisplayName(grant)}`
  const canDisclose = grant.subjectType === 'GROUP' && Boolean(grant.subjectId) && !expired
  const title = [permissionSubjectTypeLabel(grant.subjectType), members, expiry, since]
    .filter(Boolean)
    .join(' · ')
  return (
    <Box component="li" sx={{ py: 1, borderBottom: 1, borderColor: 'divider' }}>
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: { xs: 'minmax(0, 1fr) 148px 34px', sm: 'minmax(0, 1fr) 148px 34px' },
          gridTemplateAreas: { xs: '"name name name" ". role more"', sm: '"name role more"' },
          columnGap: 1.5,
          rowGap: 0.5,
          alignItems: 'center',
        }}
      >
        <Box sx={{ gridArea: 'name', minWidth: 0 }}>
          <Typography
            title={subjectName}
            sx={{
              fontSize: 13.5,
              fontWeight: 600,
              whiteSpace: 'nowrap',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
            }}
          >
            {subjectName}
          </Typography>
          <Typography
            component="div"
            title={title}
            sx={{
              fontSize: 12,
              color: 'text.secondary',
              whiteSpace: 'nowrap',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
            }}
          >
            {permissionSubjectTypeLabel(grant.subjectType)}
            {members &&
              (canDisclose ? (
                <>
                  {' · '}
                  <Link
                    component="button"
                    type="button"
                    underline="hover"
                    aria-expanded={membersOpen}
                    aria-label={`Mitglieder der Gruppe „${subjectName}“ ${membersOpen ? 'verbergen' : 'anzeigen'}`}
                    onClick={() => setMembersOpen((open) => !open)}
                    sx={{ fontSize: 'inherit', verticalAlign: 'baseline' }}
                  >
                    {members} ›
                  </Link>
                </>
              ) : (
                ` · ${members}`
              ))}
            {expiry && (
              <Box component="span" sx={{ color: expired ? 'warning.main' : 'inherit' }}>
                {` · ${expired ? `abgelaufen (${expiry})` : expiry}`}
              </Box>
            )}
            {` · ${since}`}
          </Typography>
        </Box>
        <Select
          size="small"
          value={grant.role}
          // each select tells the screen reader its row
          inputProps={{ 'aria-label': `Rolle für ${subjectName}` }}
          onChange={(e) => onRoleChange(e.target.value as AssetRole)}
          sx={{ gridArea: 'role', fontSize: 13.5, width: 148 }}
        >
          {rolesFor(grant.subjectType).map((option) => (
            <MenuItem key={option} value={option}>
              {assetRoleLabel(option)}
            </MenuItem>
          ))}
        </Select>
        <IconButton
          aria-label={`Weitere Aktionen für ${subjectName}`}
          aria-haspopup="menu"
          size="small"
          onClick={(event) => setMenuAnchor(event.currentTarget)}
          sx={{ gridArea: 'more', justifySelf: 'end', mr: -0.75 }}
        >
          <MoreHorizIcon fontSize="small" />
        </IconButton>
        <Menu
          anchorEl={menuAnchor}
          open={menuAnchor !== null}
          onClose={() => setMenuAnchor(null)}
          anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
          transformOrigin={{ vertical: 'top', horizontal: 'right' }}
        >
          <MenuItem
            onClick={() => {
              setMenuAnchor(null)
              onRevoke()
            }}
          >
            Entfernen
          </MenuItem>
        </Menu>
      </Box>
      {/* ADR-0036 Entscheidung 9: the members only on explicit request; an expired grant
          holds nothing any more, the service then answers as for an unknown group. */}
      {membersOpen && canDisclose && (
        <GroupMembersDisclosure
          key={grant.subjectId}
          requested
          onHide={() => setMembersOpen(false)}
          groupLabel={grant.subjectDisplayName ?? subjectName}
          load={(offset, limit) =>
            getGrantedGroupMembers(assetType, assetId, grant.subjectId as string, offset, limit)
          }
        />
      )}
    </Box>
  )
}
