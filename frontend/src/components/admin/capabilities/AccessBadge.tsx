import Box from '@mui/material/Box'
import { alpha } from '@mui/material/styles'
import AdminPanelSettingsOutlinedIcon from '@mui/icons-material/AdminPanelSettingsOutlined'
import GroupsOutlinedIcon from '@mui/icons-material/GroupsOutlined'
import PublicOutlinedIcon from '@mui/icons-material/PublicOutlined'
import { blue, radius } from '../../../theme/tokens'
import type { AccessLevel } from './capabilityAccess'

const ICONS = {
  ALL: PublicOutlinedIcon,
  SELECTED: GroupsOutlinedIcon,
  ADMIN_ONLY: AdminPanelSettingsOutlinedIcon,
} as const

/**
 * The state of a right as a pill. The three states differ in icon, text and outline - never in
 * colour alone (WCAG 1.4.1): all accounts filled in the accent, named subjects outlined, the
 * system administration alone dashed and muted.
 */
export default function AccessBadge({ tone, label }: { tone: AccessLevel; label: string }) {
  const Icon = ICONS[tone]
  return (
    <Box
      component="span"
      sx={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 0.625,
        px: 1.25,
        py: 0.375,
        borderRadius: `${radius.pill}px`,
        fontSize: 12.5,
        fontWeight: 500,
        lineHeight: 1.4,
        whiteSpace: 'nowrap',
        border: 1,
        ...(tone === 'ALL' && {
          // One step darker than the accent in the light scheme: the tinted pill also sits on
          // hovered rows, where the accent itself drops below 4.5:1.
          color: (t) => (t.palette.mode === 'light' ? blue[800] : t.palette.primary.main),
          bgcolor: (t) => alpha(t.palette.primary.main, 0.08),
          borderColor: (t) => alpha(t.palette.primary.main, 0.24),
        }),
        ...(tone === 'SELECTED' && {
          color: 'text.primary',
          bgcolor: 'background.paper',
          borderColor: 'text.secondary',
        }),
        ...(tone === 'ADMIN_ONLY' && {
          color: 'text.secondary',
          bgcolor: 'transparent',
          borderColor: 'divider',
          borderStyle: 'dashed',
        }),
      }}
    >
      <Icon aria-hidden="true" sx={{ fontSize: 15 }} />
      {label}
    </Box>
  )
}
