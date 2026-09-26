import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import CorporateFareOutlinedIcon from '@mui/icons-material/CorporateFareOutlined'
import GroupWorkOutlinedIcon from '@mui/icons-material/GroupWorkOutlined'
import type { GroupListResponse } from '../../../types/api'
import { InfoHint } from '../list/AdminList'
import { groupOriginExplanation } from './groupListLabels'

/**
 * Die Herkunft einer Gruppe, gebaut wie die Herkunft eines Kontos (#1978): „Intern" für eine
 * Gruppe dieser Installation, Gebäude und Anbietername für eine Gruppe eines Identitätsanbieters.
 * Hinter dem Anbieter erklärt ein Info-Symbol in ganzen Sätzen, was diese Herkunft für die
 * Mitglieder bedeutet - die Art einer Gruppe braucht damit keine eigene Spalte.
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
  const explanation = groupOriginExplanation(group)
  if (!explanation) return tag
  return (
    <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center', minWidth: 0 }}>
      <Box sx={{ minWidth: 0 }} title={label}>
        {tag}
      </Box>
      <Box sx={{ flex: 'none', display: 'inline-flex' }}>
        <InfoHint label="Herkunft" reason={explanation} />
      </Box>
    </Stack>
  )
}
