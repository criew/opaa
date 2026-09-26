import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import CorporateFareOutlinedIcon from '@mui/icons-material/CorporateFareOutlined'
import GroupWorkOutlinedIcon from '@mui/icons-material/GroupWorkOutlined'
import type { GroupListResponse } from '../../../types/api'
import { groupMaintenanceLabel } from './groupListLabels'

/**
 * Die Herkunft einer Gruppe, gebaut wie die Herkunft eines Kontos (#1978): „Intern" für eine
 * Gruppe dieser Installation, Gebäude und Anbietername für eine Gruppe eines Identitätsanbieters,
 * darunter der Pflegeweg („bei Anmeldung", „Verzeichnisabgleich"). Die Art einer Gruppe steht
 * damit hier und braucht keine eigene Spalte. Das Symbol trägt keine eigene Bedeutung.
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
  const maintenance = groupMaintenanceLabel(group)
  if (!group.provider) return tag
  return (
    <Box sx={{ minWidth: 0 }}>
      <Tooltip title={label}>{tag}</Tooltip>
      {maintenance && (
        <Typography sx={{ fontSize: 12, color: 'text.secondary', pl: '19px' }}>
          {maintenance}
        </Typography>
      )}
    </Box>
  )
}
