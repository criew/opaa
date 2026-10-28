import Box from '@mui/material/Box'
import AdminPanelSettingsOutlinedIcon from '@mui/icons-material/AdminPanelSettingsOutlined'
import GroupsOutlinedIcon from '@mui/icons-material/GroupsOutlined'
import PublicOutlinedIcon from '@mui/icons-material/PublicOutlined'
import { darkRoles, lightRoles } from '@/shared/theme/tokens'
import { outlinedBadgeSx } from '@/shared/ui/badgeStyles'
import type { AccessLevel } from './capabilityAccess'

const ICONS = {
  ALL: PublicOutlinedIcon,
  SELECTED: GroupsOutlinedIcon,
  ADMIN_ONLY: AdminPanelSettingsOutlinedIcon,
} as const

/**
 * The state of a right as a quiet label (guidelines 5.5). The three states differ in icon, text
 * and outline - never in colour (WCAG 1.4.1): all accounts on the ink surface, named subjects
 * outlined, the system administration alone dashed.
 */
export default function AccessBadge({ tone, label }: { tone: AccessLevel; label: string }) {
  const Icon = ICONS[tone]
  return (
    <Box
      component="span"
      sx={{
        ...outlinedBadgeSx,
        display: 'inline-flex',
        alignItems: 'center',
        gap: 0.625,
        px: 1.25,
        py: 0.375,
        lineHeight: 1.4,
        // Opaque in every state: the label also sits on hovered rows and keeps its own contrast.
        bgcolor:
          tone === 'ALL'
            ? (t) => (t.palette.mode === 'dark' ? darkRoles : lightRoles).bg3
            : 'background.paper',
        ...(tone === 'ADMIN_ONLY' && { borderStyle: 'dashed' }),
      }}
    >
      <Icon aria-hidden="true" sx={{ fontSize: 14 }} />
      {label}
    </Box>
  )
}
