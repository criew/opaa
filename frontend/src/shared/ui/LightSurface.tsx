import { useMemo, type ReactNode } from 'react'
import { ThemeProvider, useTheme } from '@mui/material/styles'
import { useBrandingStore } from '@/shared/branding/brandingStore'
import { createAppTheme } from '@/shared/theme/theme'

/**
 * Renders its content in the light scheme, whatever the scheme around it: the paper of a document
 * stays light in the dark scheme too, so text, links and marks on it keep the light roles and
 * their contrast. The operator's accent carries over.
 */
export default function LightSurface({ children }: { children: ReactNode }) {
  const outer = useTheme()
  const primaryColor = useBrandingStore((state) => state.branding.primaryColor)
  const dark = outer.palette.mode === 'dark'
  const light = useMemo(
    () => (dark ? createAppTheme('light', { primaryColor }) : null),
    [dark, primaryColor],
  )
  return light ? <ThemeProvider theme={light}>{children}</ThemeProvider> : <>{children}</>
}
