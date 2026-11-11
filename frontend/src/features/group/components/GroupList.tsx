import type { ReactNode } from 'react'
import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import type { GroupEffectsResponse, GroupListResponse } from '@/shared/api/types'
import type { GroupSortField } from '../api/groupAdminApi'
import {
  INITIAL_GROUP_FILTERS,
  narrowsGroups,
  useGroupAdminListStore,
} from '../stores/groupAdminListStore'
import MetaBadge from '@/shared/ui/MetaBadge'
import EmptyState from '@/shared/ui/states/EmptyState'
import LoadError from '@/shared/ui/states/LoadError'
import LoadingState from '@/shared/ui/states/LoadingState'
import AdminList, {
  StateLabel,
  type AdminListColumn,
  type SortBinding,
} from '@/shared/ui/list/AdminList'
import GroupOriginTag from './GroupOriginTag'
import GroupRowMenu from './GroupRowMenu'
import { GROUP_STATE_COLOR, GROUP_STATE_LABEL, groupStateReason } from './groupListLabels'
import { GROUP_UNUSED_DETAIL, groupUsageDetails, groupUsageShort } from './groupUsageLabels'
import { fontSize } from '@/shared/theme/tokens'

const SORT_LABEL: Record<GroupSortField, string> = {
  name: 'Name',
  kind: 'Art',
  origin: 'Herkunft',
  memberCount: 'Mitglieder',
  state: 'Zustand',
  createdAt: 'Angelegt',
}

/** What the screen reader announces after a click - for ranked columns the order itself. */
const SORT_DESCRIPTION: Record<GroupSortField, string> = {
  name: 'Name',
  kind: 'Art: Ad-hoc-Gruppe, aus dem Anbieter, Organisationseinheit',
  origin: 'Herkunft, interne Gruppen zuerst',
  memberCount: 'Zahl der Mitglieder',
  state:
    'Zustand: aufgelöst, Anbieter deaktiviert, nicht mehr gepflegt, deaktiviert, nicht freigegeben, aktiv',
  createdAt: 'Anlagedatum',
}

function useGroupSort(): SortBinding<GroupSortField> {
  const filters = useGroupAdminListStore((s) => s.filters)
  const setFilters = useGroupAdminListStore((s) => s.setFilters)
  return {
    sort: filters.sort,
    direction: filters.direction,
    onSort: (sort, direction) => void setFilters({ sort, direction }),
    labels: SORT_LABEL,
    descriptions: SORT_DESCRIPTION,
  }
}

export interface GroupRowHandlers {
  onEdit: (group: GroupListResponse) => void
  onMembers: (group: GroupListResponse) => void
  onTransfer: (group: GroupListResponse) => void
  onDeleted: () => void
  onChanged: () => void
}

/**
 * Name and the one line that identifies the group besides it: the source path of a directory
 * group, otherwise its description. The marks that change how a group may be used sit by the name.
 */
function GroupNameCell({ group }: { group: GroupListResponse }) {
  const secondLine = group.sourcePath ?? group.description
  return (
    <Box sx={{ minWidth: 0 }}>
      <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center', minWidth: 0 }}>
        <Typography
          title={group.name}
          sx={{
            fontSize: fontSize.text,
            fontWeight: 600,
            minWidth: 0,
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
          }}
        >
          {group.name}
        </Typography>
        {group.protectedGroup && (
          <Box component="span" sx={{ flex: 'none' }}>
            <MetaBadge>geschützt</MetaBadge>
          </Box>
        )}
        {group.provider?.external && (
          <Box component="span" sx={{ flex: 'none' }}>
            <MetaBadge>extern</MetaBadge>
          </Box>
        )}
      </Stack>
      {secondLine && (
        <Typography
          sx={{
            fontSize: fontSize.meta,
            color: 'text.secondary',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
          }}
          title={secondLine}
        >
          {secondLine}
        </Typography>
      )}
    </Box>
  )
}

function GroupStateCell({ group }: { group: GroupListResponse }) {
  const reason = groupStateReason(group)
  return (
    <StateLabel
      color={GROUP_STATE_COLOR[group.state]}
      label={GROUP_STATE_LABEL[group.state]}
      hint={reason ? { label: 'Grund', reason } : null}
    />
  )
}

/**
 * What the group is used for in one line - libraries, spaces, ownership, create rights - with every
 * figure spelled out in the tooltip. Answers the question before cleaning up: does anything hang on
 * this group?
 */
function UsageCell({ effects }: { effects: GroupEffectsResponse | undefined }) {
  if (!effects)
    return <Typography sx={{ fontSize: fontSize.text, color: 'text.disabled' }}>–</Typography>
  const short = groupUsageShort(effects)
  const details = groupUsageDetails(effects)
  const unused = details.length === 0
  const tooltip = unused ? GROUP_UNUSED_DETAIL : `Verwendet für: ${details.join('; ')}.`
  return (
    <Tooltip title={tooltip}>
      <Typography
        tabIndex={0}
        aria-label={`Verwendung: ${tooltip}`}
        sx={{
          fontSize: fontSize.meta,
          color: unused ? 'text.secondary' : 'text.primary',
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          whiteSpace: 'nowrap',
          cursor: 'help',
          borderRadius: 0.5,
        }}
      >
        {short}
      </Typography>
    </Tooltip>
  )
}

/**
 * The list of all groups of the organization, built like the account list: on desktop a
 * table, below tablet width a list, searched, filtered, sorted and paged server-side.
 * What a group is used for is shown as a summary; the member list is not -
 * its retrieval is an audit event and happens only in the „Mitglieder" dialog (ADR-0036/4, /9).
 */
export default function GroupList({
  createAction,
  ...handlers
}: GroupRowHandlers & {
  /** The create button of the page header, repeated by the first-time empty state. */
  createAction: ReactNode
}) {
  const groups = useGroupAdminListStore((s) => s.groups)
  const effects = useGroupAdminListStore((s) => s.effects)
  const total = useGroupAdminListStore((s) => s.total)
  const size = useGroupAdminListStore((s) => s.size)
  const isLoading = useGroupAdminListStore((s) => s.isLoading)
  const error = useGroupAdminListStore((s) => s.error)
  const filters = useGroupAdminListStore((s) => s.filters)
  const loadGroups = useGroupAdminListStore((s) => s.loadGroups)
  const setFilters = useGroupAdminListStore((s) => s.setFilters)
  const sort = useGroupSort()

  // After a loading error only the notice stands - an empty list below it would look like a
  // filter without matches.
  if (error) {
    return (
      <LoadError
        message="Gruppen konnten nicht geladen werden."
        detail={error}
        onRetry={() => void loadGroups()}
      />
    )
  }

  if (isLoading && groups.length === 0) {
    return <LoadingState />
  }

  if (groups.length === 0) {
    const { query, origin, providerId, kind, state } = INITIAL_GROUP_FILTERS
    const narrowed = narrowsGroups(filters)
    return narrowed ? (
      <EmptyState
        framed
        kind="noMatch"
        onReset={() => void setFilters({ query, origin, providerId, kind, state })}
      />
    ) : (
      <EmptyState framed message="Noch keine Gruppen." action={createAction} />
    )
  }

  const columns: AdminListColumn<GroupListResponse, GroupSortField>[] = [
    {
      id: 'name',
      head: 'Name',
      sortFields: ['name'],
      cell: (group) => <GroupNameCell group={group} />,
    },
    {
      id: 'origin',
      head: 'Herkunft',
      sortFields: ['origin'],
      width: '17%',
      card: 'line',
      cell: (group) => <GroupOriginTag group={group} />,
    },
    {
      id: 'memberCount',
      head: 'Mitglieder',
      sortFields: ['memberCount'],
      width: '15%',
      align: 'right',
      cell: (group) => group.memberCount,
    },
    {
      id: 'state',
      head: 'Zustand',
      sortFields: ['state'],
      width: '17%',
      cell: (group) => <GroupStateCell group={group} />,
    },
    {
      id: 'usage',
      head: 'Verwendung',
      width: '18%',
      cell: (group) => <UsageCell effects={effects[group.id]} />,
    },
  ]

  return (
    <AdminList
      label="Gruppen"
      rows={groups}
      minWidth={880}
      rowKey={(group) => group.id}
      rowLabel={(group) => group.name}
      columns={columns}
      sort={sort}
      rowActions={(group) => <GroupRowMenu group={group} {...handlers} />}
      pager={{ total, size, page: filters.page, onPage: (page) => void setFilters({ page }) }}
    />
  )
}
