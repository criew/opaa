import { useId, useMemo, useRef, useState } from 'react'
import Box from '@mui/material/Box'
import Divider from '@mui/material/Divider'
import IconButton from '@mui/material/IconButton'
import Link from '@mui/material/Link'
import ListItemIcon from '@mui/material/ListItemIcon'
import Menu from '@mui/material/Menu'
import MenuItem from '@mui/material/MenuItem'
import Select from '@mui/material/Select'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import visuallyHidden from '@mui/utils/visuallyHidden'
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutlined'
import GroupsOutlinedIcon from '@mui/icons-material/GroupsOutlined'
import LockOutlinedIcon from '@mui/icons-material/LockOutlined'
import MoreHorizIcon from '@mui/icons-material/MoreHoriz'
import PersonOutlineIcon from '@mui/icons-material/PersonOutlined'
import type { SpaceMemberResponse, SpaceRole } from '@/shared/api/types'
import { getSpaceAccessDerivation, getSpaceGroupMembers } from '../api/spaceApi'
import { confirmAction } from '@/shared/stores/confirmStore'
import { groupMemberCountLabel } from '@/features/asset'
import { spaceRoleLabel } from '@/shared/utils/labels'
import { AccessDerivation } from '@/features/asset'
import { GroupMembersDisclosure } from '@/features/principal'
import MetaBadge from '@/shared/ui/MetaBadge'
import { memberLabelOf } from './memberLabel'

const editableRoles: SpaceRole[] = ['MEMBER', 'CURATOR', 'ADMIN']

const OWNER_ROLE_HINT =
  'Den Eigentümer ändern Sie über „Zum Eigentümer machen“ bei einem anderen Mitglied.'

/** Stands where an editable role choice carries its arrow; MUI passes the arrow's class. */
function OwnerLockIcon({ className }: { className?: string }) {
  return (
    <LockOutlinedIcon
      aria-hidden
      className={className}
      sx={{ fontSize: 16, color: 'text.secondary' }}
    />
  )
}

/** From this number of entries on, a search field is shown above the list. */
const FILTER_THRESHOLD = 10

const roleOrder: Record<SpaceRole, number> = { ADMIN: 0, CURATOR: 1, MEMBER: 2 }

function ownsSpace(member: SpaceMemberResponse, ownerId: string): boolean {
  return member.subjectType === 'USER' && member.subjectId === ownerId
}

/** Owner first, then administrator, curator, member, within the role by name. */
function sortMembers(members: SpaceMemberResponse[], ownerId: string): SpaceMemberResponse[] {
  return [...members].sort((a, b) => {
    const ownerFirst = Number(ownsSpace(b, ownerId)) - Number(ownsSpace(a, ownerId))
    if (ownerFirst !== 0) return ownerFirst
    const byRole = roleOrder[a.role] - roleOrder[b.role]
    if (byRole !== 0) return byRole
    return memberLabelOf(a).localeCompare(memberLabelOf(b), 'de', { sensitivity: 'base' })
  })
}

/** What is shown below an expanded row: a person's derivation or the members. */
type Expansion = { memberId: string; kind: 'derivation' | 'groupMembers' } | null

interface SpaceMemberListProps {
  spaceId: string
  ownerId: string
  members: SpaceMemberResponse[]
  /** Only an administrator of the space changes roles and memberships. */
  canManage: boolean
  isOwner: boolean
  onRoleChange: (member: SpaceMemberResponse, role: SpaceRole) => Promise<void>
  /** Called only after the confirmation. */
  onRemove: (member: SpaceMemberResponse) => Promise<void>
  /** Called only after the confirmation. */
  onMakeOwner: (member: SpaceMemberResponse) => Promise<void>
}

/**
 * The existing members of a space: owner first, then by role and name,
 * persons and groups mixed. Every row carries at most the role and a „⋯“ menu with the
 * entries permitted for the row and the own role.
 */
export default function SpaceMemberList({
  spaceId,
  ownerId,
  members,
  canManage,
  isOwner,
  onRoleChange,
  onRemove,
  onMakeOwner,
}: SpaceMemberListProps) {
  const [filter, setFilter] = useState('')
  const ownerHintId = `space-owner-hint-${useId()}`
  const [expansion, setExpansion] = useState<Expansion>(null)
  const [menu, setMenu] = useState<{ anchor: HTMLElement; memberId: string } | null>(null)
  /** The "⋯" button of every row, so hiding an expansion can hand the focus back to its row. */
  const menuButtons = useRef(new Map<string, HTMLButtonElement>())

  function hideExpansion(memberId: string) {
    setExpansion(null)
    menuButtons.current.get(memberId)?.focus()
  }

  const isOwnerRow = (member: SpaceMemberResponse) => ownsSpace(member, ownerId)
  const sorted = useMemo(() => sortMembers(members, ownerId), [members, ownerId])
  const showFilter = members.length > FILTER_THRESHOLD
  const needle = filter.trim().toLocaleLowerCase('de')
  const visible =
    showFilter && needle
      ? sorted.filter((member) => memberLabelOf(member).toLocaleLowerCase('de').includes(needle))
      : sorted

  function menuEntriesOf(member: SpaceMemberResponse) {
    const isGroup = member.subjectType === 'GROUP'
    const ownerRow = isOwnerRow(member)
    return {
      derivation: !isGroup && !ownerRow,
      groupMembers: isGroup && (canManage || isOwner),
      makeOwner: !isGroup && !ownerRow && (canManage || isOwner),
      remove: canManage && !ownerRow,
    }
  }

  async function remove(member: SpaceMemberResponse) {
    const confirmed = await confirmAction({
      question: `${memberLabelOf(member)} aus diesem Space entfernen?`,
      confirmLabel: 'Entfernen',
      tone: 'danger',
    })
    if (confirmed) await onRemove(member)
  }

  async function makeOwner(member: SpaceMemberResponse) {
    const confirmed = await confirmAction({
      question: `Verantwortung an ${memberLabelOf(member)} übertragen?`,
      confirmLabel: 'Übertragen',
      tone: 'caution',
    })
    if (confirmed) await onMakeOwner(member)
  }

  const menuMember = menu ? members.find((member) => member.id === menu.memberId) : undefined
  const menuEntries = menuMember ? menuEntriesOf(menuMember) : null
  const closeMenu = () => setMenu(null)

  return (
    <Stack spacing={1}>
      {showFilter && (
        <TextField
          size="small"
          type="search"
          value={filter}
          onChange={(event) => setFilter(event.target.value)}
          placeholder="Name suchen …"
          slotProps={{ htmlInput: { 'aria-label': 'Mitglieder filtern' } }}
          sx={{ maxWidth: 360 }}
        />
      )}
      {showFilter && visible.length === 0 && (
        <Typography sx={{ color: 'text.secondary', fontSize: 13.5 }}>
          Kein Mitglied passt zu „{filter.trim()}“.
        </Typography>
      )}
      <Stack spacing={0}>
        {visible.map((member) => {
          const isGroup = member.subjectType === 'GROUP'
          const ownerRow = isOwnerRow(member)
          const label = memberLabelOf(member)
          const entries = menuEntriesOf(member)
          const hasMenu = Object.values(entries).some(Boolean)
          const sizeHint = isGroup ? groupMemberCountLabel(member) : null
          const namelessRow = !member.displayName && !member.protectedGroup
          const Icon = isGroup ? GroupsOutlinedIcon : PersonOutlineIcon
          const expanded = expansion?.memberId === member.id ? expansion.kind : null
          return (
            <Box
              key={member.id}
              data-testid="space-member-row"
              sx={{ py: 1.25, '& + &': { borderTop: 1, borderColor: 'divider' } }}
            >
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
                <Icon fontSize="small" aria-hidden sx={{ color: 'text.secondary' }} />
                <Stack spacing={0.25} sx={{ minWidth: 0, flexGrow: 1 }}>
                  <Typography
                    data-testid="space-member-name"
                    sx={{
                      fontSize: 13.5,
                      ...(namelessRow ? { fontFamily: 'monospace' } : {}),
                      ...(member.protectedGroup ? { fontStyle: 'italic' } : {}),
                    }}
                  >
                    {label}
                  </Typography>
                  {isGroup && (
                    <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                      {sizeHint ? `Gruppe · ${sizeHint}` : 'Gruppe'}
                    </Typography>
                  )}
                </Stack>
                {ownerRow && canManage ? (
                  // Same look as the other role choices, but read-only with a lock instead of the
                  // arrow. Why, and where the owner is changed instead, reaches hover, keyboard
                  // focus and screen readers alike.
                  <>
                    <Tooltip title={OWNER_ROLE_HINT} describeChild>
                      <Select
                        size="small"
                        value="OWNER"
                        readOnly
                        IconComponent={OwnerLockIcon}
                        inputProps={{ 'aria-label': `Rolle von „${label}“` }}
                        SelectDisplayProps={{ 'aria-describedby': ownerHintId }}
                        sx={{ minWidth: 140 }}
                      >
                        <MenuItem value="OWNER">Eigentümer</MenuItem>
                      </Select>
                    </Tooltip>
                    <Box component="span" id={ownerHintId} sx={visuallyHidden}>
                      {OWNER_ROLE_HINT}
                    </Box>
                  </>
                ) : ownerRow ? (
                  <MetaBadge>Eigentümer</MetaBadge>
                ) : canManage ? (
                  <Select
                    size="small"
                    value={member.role}
                    onChange={(event) => void onRoleChange(member, event.target.value as SpaceRole)}
                    inputProps={{ 'aria-label': `Rolle von „${label}“` }}
                    sx={{ minWidth: 140 }}
                  >
                    {editableRoles.map((role) => (
                      <MenuItem key={role} value={role}>
                        {spaceRoleLabel(role)}
                      </MenuItem>
                    ))}
                  </Select>
                ) : (
                  <MetaBadge>{spaceRoleLabel(member.role)}</MetaBadge>
                )}
                {hasMenu ? (
                  <IconButton
                    size="small"
                    aria-label={`Weitere Aktionen für „${label}“`}
                    aria-haspopup="menu"
                    ref={(element: HTMLButtonElement | null) => {
                      if (element) menuButtons.current.set(member.id, element)
                      else menuButtons.current.delete(member.id)
                    }}
                    onClick={(event) =>
                      setMenu({ anchor: event.currentTarget, memberId: member.id })
                    }
                  >
                    <MoreHorizIcon fontSize="small" />
                  </IconButton>
                ) : (
                  // Keeps the role column aligned with the rows that do carry a menu.
                  <Box sx={{ width: 34, flexShrink: 0 }} />
                )}
              </Box>
              {expanded === 'derivation' && (
                <Stack spacing={0.5} sx={{ pl: 4.5, pt: 0.5 }}>
                  <AccessDerivation
                    target={{ kind: 'space', spaceId, userId: member.subjectId }}
                    load={getSpaceAccessDerivation}
                    subjectName={label}
                  />
                  <Link
                    component="button"
                    type="button"
                    sx={{ alignSelf: 'flex-end', fontSize: 12 }}
                    onClick={() => hideExpansion(member.id)}
                  >
                    Schließen
                  </Link>
                </Stack>
              )}
              {expanded === 'groupMembers' && (
                <Box sx={{ pl: 4.5 }}>
                  <GroupMembersDisclosure
                    requested
                    groupLabel={
                      member.protectedGroup
                        ? 'Geschützte Gruppe'
                        : (member.displayName ?? 'ohne Namen')
                    }
                    load={(offset, limit) =>
                      getSpaceGroupMembers(spaceId, member.subjectId, offset, limit)
                    }
                    onHide={() => hideExpansion(member.id)}
                  />
                </Box>
              )}
            </Box>
          )
        })}
      </Stack>
      <Menu
        anchorEl={menu?.anchor ?? null}
        open={menu !== null && menuMember !== undefined}
        onClose={closeMenu}
        slotProps={{
          list: {
            'aria-label': menuMember
              ? `Weitere Aktionen für „${memberLabelOf(menuMember)}“`
              : undefined,
          },
        }}
      >
        {menuMember && menuEntries?.derivation && (
          <MenuItem
            onClick={() => {
              closeMenu()
              setExpansion({ memberId: menuMember.id, kind: 'derivation' })
            }}
          >
            Warum hat {memberLabelOf(menuMember)} Zugriff?
          </MenuItem>
        )}
        {menuMember && menuEntries?.groupMembers && (
          <MenuItem
            onClick={() => {
              closeMenu()
              setExpansion({ memberId: menuMember.id, kind: 'groupMembers' })
            }}
          >
            Mitglieder der Gruppe anzeigen
          </MenuItem>
        )}
        {menuMember && menuEntries?.makeOwner && (
          <MenuItem
            onClick={() => {
              closeMenu()
              void makeOwner(menuMember)
            }}
          >
            Zum Eigentümer machen
          </MenuItem>
        )}
        {menuMember &&
          menuEntries?.remove &&
          (menuEntries.derivation || menuEntries.groupMembers || menuEntries.makeOwner) && (
            <Divider />
          )}
        {menuMember && menuEntries?.remove && (
          <MenuItem
            onClick={() => {
              closeMenu()
              void remove(menuMember)
            }}
          >
            <ListItemIcon>
              <DeleteOutlineIcon fontSize="small" color="error" />
            </ListItemIcon>
            Aus Space entfernen
          </MenuItem>
        )}
      </Menu>
    </Stack>
  )
}
