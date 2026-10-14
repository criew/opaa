import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import CorporateFareOutlinedIcon from '@mui/icons-material/CorporateFareOutlined'
import GroupWorkOutlinedIcon from '@mui/icons-material/GroupWorkOutlined'
import type { GroupListResponse } from '@/shared/api/types'
import { InfoHint } from '@/shared/ui/list/AdminList'
import { groupOriginExplanation } from './groupListLabels'

/**
 * The origin of a group, built like the origin of an account: „Intern" for a
 * group of this installation, building and provider name for a group of an identity provider.
 * Behind the provider an info icon explains in full sentences what this origin means for the
 * members - so the kind of a group needs no column of its own.
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
