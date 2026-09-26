import type { ReactNode } from 'react'
import Box from '@mui/material/Box'
import type { SvgIconComponent } from '@mui/icons-material'
import BadgeOutlinedIcon from '@mui/icons-material/BadgeOutlined'
import GridViewOutlinedIcon from '@mui/icons-material/GridViewOutlined'
import GroupsOutlinedIcon from '@mui/icons-material/GroupsOutlined'
import HomeOutlinedIcon from '@mui/icons-material/HomeOutlined'
import type { LocalHandoverScope } from '../../types/api'
import { SYSTEM_ROLE_LABEL } from '../admin/users/localUserLabels'
import { radius } from '../../theme/tokens'

interface TileProps {
  icon: SvgIconComponent
  label: string
  /** Counts are shown large; names and roles as text, cut off with the full value as tooltip. */
  value: ReactNode
  isCount?: boolean
  title?: string
}

function Tile({ icon: Icon, label, value, isCount = false, title }: TileProps) {
  return (
    <Box
      component="li"
      sx={{
        display: 'flex',
        alignItems: 'center',
        gap: 1.25,
        minWidth: 0,
        p: 1.5,
        border: 1,
        borderColor: 'divider',
        borderRadius: `${radius.md}px`,
        bgcolor: 'background.paper',
      }}
    >
      <Box
        aria-hidden
        sx={{
          display: 'grid',
          placeItems: 'center',
          width: 36,
          height: 36,
          flex: 'none',
          borderRadius: `${radius.sm}px`,
          bgcolor: 'action.hover',
          color: 'primary.main',
        }}
      >
        <Icon sx={{ fontSize: 20 }} />
      </Box>
      <Box sx={{ minWidth: 0 }}>
        <Box sx={{ fontSize: 12, color: 'text.secondary' }}>{label}</Box>
        <Box
          title={title}
          sx={{
            fontSize: isCount ? 20 : 14.5,
            lineHeight: isCount ? 1.2 : 1.4,
            fontWeight: 600,
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
          }}
        >
          {value}
        </Box>
      </Box>
    </Box>
  )
}

/**
 * What moves with the account, as four tiles (ADR-0033, Entscheidung 12). The page shows counts on
 * purpose, not lists: it is reachable with the link alone, and a count stays one tile however many
 * spaces and groups there are.
 */
export default function HandoverScopeSummary({ scope }: { scope: LocalHandoverScope }) {
  const personalSpace = scope.personalSpaceName ?? 'Noch keiner angelegt'
  return (
    <Box
      component="ul"
      sx={{
        listStyle: 'none',
        m: 0,
        p: 0,
        mt: 1.25,
        display: 'grid',
        gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr' },
        gap: 1,
        textAlign: 'left',
      }}
    >
      <Tile
        icon={HomeOutlinedIcon}
        label="Persönlicher Space"
        value={personalSpace}
        title={personalSpace}
      />
      <Tile icon={BadgeOutlinedIcon} label="Rolle" value={SYSTEM_ROLE_LABEL[scope.systemRole]} />
      <Tile icon={GridViewOutlinedIcon} label="Spaces" value={scope.spaceMemberships} isCount />
      <Tile icon={GroupsOutlinedIcon} label="Gruppen" value={scope.groupMemberships} isCount />
    </Box>
  )
}
