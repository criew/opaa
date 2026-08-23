import Box from '@mui/material/Box'
import Link from '@mui/material/Link'
import { NavLink, useLocation } from 'react-router'

const SECTIONS = [
  { label: 'Gruppen', to: '/admin/groups' },
  { label: 'Branding', to: '/admin/branding' },
  { label: 'Modelle', to: '/admin/models' },
] as const

/**
 * Quiet link row between the administration pages (#786 review, finding 1): the rail's single
 * "Admin" entry replaced the sidebar's three per-page links, which left the sibling pages
 * unreachable through the interface. A deliberate stopgap - #787's secondary column takes over
 * this role and removes this component.
 */
export default function AdminSectionNav() {
  const location = useLocation()

  return (
    <Box component="nav" aria-label="Administration" sx={{ display: 'flex', gap: 2.5, mb: 2 }}>
      {SECTIONS.map((section) => {
        const active = location.pathname === section.to
        return (
          <Link
            key={section.to}
            component={NavLink}
            to={section.to}
            aria-current={active ? 'page' : undefined}
            underline={active ? 'always' : 'hover'}
            sx={{
              fontSize: 13,
              fontWeight: active ? 600 : 400,
              // text.primary, not the accent: blue-500 on white is 3.29:1 (#634) and fails
              // WCAG AA at this size (#792); underline and weight already carry the state.
              color: active ? 'text.primary' : 'text.secondary',
            }}
          >
            {section.label}
          </Link>
        )
      })}
    </Box>
  )
}
