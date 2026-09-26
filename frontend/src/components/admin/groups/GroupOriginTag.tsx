import Stack from '@mui/material/Stack'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import CorporateFareOutlinedIcon from '@mui/icons-material/CorporateFareOutlined'
import GroupWorkOutlinedIcon from '@mui/icons-material/GroupWorkOutlined'
import type { GroupListResponse } from '../../../types/api'
import { groupMechanismLabel } from '../../groups/groupOriginLabels'

/**
 * Die Herkunft einer Gruppe, gebaut wie die Herkunft eines Kontos (#1978): „Intern" für eine
 * Gruppe dieser Installation, Gebäude und Anbietername für eine Gruppe eines Identitätsanbieters.
 * Das Symbol trägt keine eigene Bedeutung - das Wort daneben tut es; der Tooltip nennt den
 * Mechanismus, der die Gruppe pflegt.
 */
export default function GroupOriginTag({ group }: { group: GroupListResponse }) {
  const internal = !group.provider
  const label = group.provider?.displayName ?? 'Intern'
  const Icon = internal ? GroupWorkOutlinedIcon : CorporateFareOutlinedIcon
  const tag = (
    <Stack
      direction="row"
      spacing={0.5}
      component="span"
      sx={{ alignItems: 'center', display: 'inline-flex', minWidth: 0, maxWidth: '100%' }}
    >
      <Icon
        aria-hidden="true"
        sx={{ fontSize: 15, flex: 'none', color: internal ? 'primary.main' : 'text.secondary' }}
      />
      <Typography
        component="span"
        sx={{
          fontSize: 12.5,
          fontWeight: internal ? 600 : 500,
          color: internal ? 'text.primary' : 'text.secondary',
          minWidth: 0,
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          whiteSpace: 'nowrap',
        }}
      >
        {label}
      </Typography>
    </Stack>
  )
  if (!group.provider) return tag
  return (
    <Tooltip title={`${label} · ${groupMechanismLabel(group.provider.groupMechanism)}`}>
      {tag}
    </Tooltip>
  )
}
