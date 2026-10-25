import type { ReactNode } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { useTheme } from '@mui/material/styles'
import BrandMark from '@/shared/ui/BrandMark'
import PageHeading, { MAIN_CONTENT_ID } from '@/shared/ui/a11y/PageHeading'
import { useBrandingStore } from '@/shared/branding/brandingStore'
import { darkRoles, lightRoles } from '@/shared/theme/tokens'

interface InstallationLayoutProps {
  /** „Schritt n von m", where the assistant has steps to count. */
  progress?: string | null
  /** The step list; absent on the views without steps (completed, already installed). */
  navigation?: ReactNode
  children: ReactNode
}

/**
 * The frame of the assistant, outside the application shell: the installation's mark and the
 * page's one h1 on top, the step list in a light secondary column beside the step from `md` on,
 * above it below that. The step keeps a reading width; extra width goes to the margins.
 */
export default function InstallationLayout({
  progress,
  navigation,
  children,
}: InstallationLayoutProps) {
  const theme = useTheme()
  const roles = theme.palette.mode === 'light' ? lightRoles : darkRoles
  const productName = useBrandingStore((s) => s.branding.productName)

  return (
    <Box sx={{ minHeight: '100vh', bgcolor: 'background.default' }}>
      <Box
        component="header"
        sx={{
          display: 'flex',
          flexWrap: 'wrap',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 2,
          px: { xs: 2.5, md: 5 },
          py: 2.5,
          borderBottom: 1,
          borderColor: 'divider',
        }}
      >
        <BrandMark variant="h6" logoHeight={28} />
        {progress && (
          <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>{progress}</Typography>
        )}
      </Box>
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: navigation
            ? { xs: 'minmax(0, 1fr)', md: '280px minmax(0, 1fr)' }
            : 'minmax(0, 1fr)',
          minHeight: 'calc(100vh - 73px)',
        }}
      >
        {navigation && (
          <Box
            sx={{
              bgcolor: roles.bg2,
              borderRight: { md: `1px solid ${roles.borderStrong}` },
              borderBottom: { xs: `1px solid ${roles.borderStrong}`, md: 'none' },
              px: 1.5,
              pb: 2.5,
            }}
          >
            {navigation}
          </Box>
        )}
        <Box
          component="main"
          id={MAIN_CONTENT_ID}
          sx={{ px: { xs: 2.5, md: 6 }, py: { xs: 3, md: 5 } }}
        >
          <Box sx={{ maxWidth: 640 }}>
            <PageHeading
              title={`${productName} einrichten`}
              variant="h5"
              sx={{ fontSize: 13, fontWeight: 500, color: 'text.secondary', mb: 1.5 }}
            />
            {children}
          </Box>
        </Box>
      </Box>
    </Box>
  )
}
