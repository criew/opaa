import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import useMediaQuery from '@mui/material/useMediaQuery'
import { useTheme } from '@mui/material/styles'
import visuallyHidden from '@mui/utils/visuallyHidden'
import type { GroupEffectsResponse, GroupListResponse } from '../../../types/api'
import type { GroupSortField } from '../../../services/groupAdminApi'
import { useGroupAdminListStore } from '../../../stores/groupAdminListStore'
import MetaBadge from '../../MetaBadge'
import {
  ListEmptyState,
  ListLoading,
  ListPager,
  SortableHeadCell,
  StateLabel,
  type SortBinding,
} from '../list/AdminList'
import { adminTableSx, listCardSx } from '../list/adminListStyles'
import GroupOriginTag from './GroupOriginTag'
import GroupRowMenu from './GroupRowMenu'
import { GROUP_STATE_COLOR, GROUP_STATE_LABEL, groupStateReason } from './groupListLabels'
import { GROUP_UNUSED_DETAIL, groupUsageDetails, groupUsageShort } from './groupUsageLabels'

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
  state: 'Zustand: aufgelöst, Anbieter deaktiviert, nicht mehr gepflegt, nicht freigegeben, aktiv',
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
}

interface RowProps extends GroupRowHandlers {
  group: GroupListResponse
  effects: GroupEffectsResponse | undefined
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
            fontSize: 13.5,
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
            <MetaBadge accent>extern</MetaBadge>
          </Box>
        )}
      </Stack>
      {secondLine && (
        <Typography
          sx={{
            fontSize: 12,
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

function memberText(count: number): string {
  return count === 1 ? '1 Mitglied' : `${count} Mitglieder`
}

/**
 * What the group is used for in one line - libraries, spaces, ownership, create rights - with every
 * figure spelled out in the tooltip. Answers the question before cleaning up: does anything hang on
 * this group?
 */
function UsageCell({ effects }: { effects: GroupEffectsResponse | undefined }) {
  if (!effects) return <Typography sx={{ fontSize: 13, color: 'text.disabled' }}>–</Typography>
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
          fontSize: 12.5,
          color: unused ? 'text.secondary' : 'text.primary',
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          whiteSpace: 'nowrap',
          cursor: 'help',
          borderRadius: 0.5,
          '&:focus-visible': { outline: 2, outlineColor: 'primary.main', outlineOffset: 1 },
        }}
      >
        {short}
      </Typography>
    </Tooltip>
  )
}

function GroupTableRow({ group, effects, ...handlers }: RowProps) {
  return (
    <TableRow>
      <TableCell>
        <GroupNameCell group={group} />
      </TableCell>
      <TableCell>
        <GroupOriginTag group={group} />
      </TableCell>
      <TableCell align="right">{group.memberCount}</TableCell>
      <TableCell>
        <GroupStateCell group={group} />
      </TableCell>
      <TableCell>
        <UsageCell effects={effects} />
      </TableCell>
      <TableCell align="right">
        <GroupRowMenu group={group} {...handlers} />
      </TableCell>
    </TableRow>
  )
}

/** One group as a list entry - the layout below tablet width (guidelines 5.3). */
function GroupCard({ group, effects, ...handlers }: RowProps) {
  return (
    <Box component="article" aria-label={group.name} sx={listCardSx}>
      <Stack direction="row" spacing={1} sx={{ alignItems: 'flex-start' }}>
        <Box sx={{ minWidth: 0, flexGrow: 1 }}>
          <GroupNameCell group={group} />
          <Box sx={{ mt: 0.5 }}>
            <GroupOriginTag group={group} />
          </Box>
        </Box>
        <GroupRowMenu group={group} {...handlers} />
      </Stack>
      <Box sx={{ mt: 1 }}>
        <GroupStateCell group={group} />
      </Box>
      <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 0.75 }}>
        {memberText(group.memberCount)}
        {effects ? ` · ${groupUsageShort(effects)}` : ''}
      </Typography>
    </Box>
  )
}

function Pager() {
  const total = useGroupAdminListStore((s) => s.total)
  const size = useGroupAdminListStore((s) => s.size)
  const page = useGroupAdminListStore((s) => s.filters.page)
  const setFilters = useGroupAdminListStore((s) => s.setFilters)
  return (
    <ListPager
      total={total}
      size={size}
      page={page}
      onPage={(next) => void setFilters({ page: next })}
      countLabel={total === 1 ? '1 Gruppe' : `${total} Gruppen`}
    />
  )
}

/**
 * Die Liste aller Gruppen der Organisation (#1978), gebaut wie die Kontenliste: am Desktop eine
 * Tabelle, unter Tablet-Breite eine Liste, serverseitig durchsucht, gefiltert, sortiert und
 * geblättert. Wofür eine Gruppe verwendet wird, steht als Zusammenfassung da; die Mitgliederliste
 * nicht -
 * ihr Abruf ist ein Audit-Ereignis und geschieht erst im Dialog „Mitglieder" (ADR-0036/4, /9).
 */
export default function GroupList(handlers: GroupRowHandlers) {
  const theme = useTheme()
  const isDesktop = useMediaQuery(theme.breakpoints.up('md'))
  const groups = useGroupAdminListStore((s) => s.groups)
  const effects = useGroupAdminListStore((s) => s.effects)
  const isLoading = useGroupAdminListStore((s) => s.isLoading)
  const sort = useGroupSort()

  if (isLoading && groups.length === 0) {
    return <ListLoading label="Gruppen werden geladen …" />
  }

  if (groups.length === 0) {
    return (
      <ListEmptyState
        title="Keine Gruppe entspricht den gewählten Filtern."
        hint="Interne Gruppen und Gruppen der Identitätsanbieter stehen hier gemeinsam; die Herkunft steht an jeder Zeile."
      />
    )
  }

  return (
    <>
      {isDesktop ? (
        <Table size="small" aria-label="Gruppen" sx={adminTableSx}>
          <TableHead>
            <TableRow>
              <SortableHeadCell fields={['name']} binding={sort} />
              <SortableHeadCell fields={['origin']} binding={sort} width="19%" />
              <SortableHeadCell fields={['memberCount']} binding={sort} width="10%" align="right" />
              <SortableHeadCell fields={['state']} binding={sort} width="19%" />
              <TableCell sx={{ width: '18%' }}>Verwendung</TableCell>
              <TableCell align="right" sx={{ width: 56 }}>
                <span style={visuallyHidden}>Aktionen</span>
              </TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {groups.map((group) => (
              <GroupTableRow
                key={group.id}
                group={group}
                effects={effects[group.id]}
                {...handlers}
              />
            ))}
          </TableBody>
        </Table>
      ) : (
        <Stack spacing={0}>
          {groups.map((group) => (
            <GroupCard key={group.id} group={group} effects={effects[group.id]} {...handlers} />
          ))}
        </Stack>
      )}
      <Pager />
    </>
  )
}
