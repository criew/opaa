import Avatar from '@mui/material/Avatar'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import ToggleButton from '@mui/material/ToggleButton'
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup'
import DarkModeIcon from '@mui/icons-material/DarkMode'
import LightModeIcon from '@mui/icons-material/LightMode'
import SettingsBrightnessIcon from '@mui/icons-material/SettingsBrightness'
import Button from '@mui/material/Button'
import Link from '@mui/material/Link'
import { Link as RouterLink } from 'react-router'
import { useAuthStore } from '../stores/authStore'
import type { AuthMode } from '../types/auth'
import { useUiStore } from '../stores/uiStore'
import type { ThemeMode } from '../stores/uiStore'
import { useBrandingStore } from '../stores/brandingStore'
import { resolveThemeMode } from '../theme/colorScheme'
import GlobalBadge from '../components/GlobalBadge'
import PageHeading from '../components/a11y/PageHeading'
import SectionHead from '../components/SectionHead'

/** Mockup 2c: how the account signed in, next to the address - never a technical mode name. */
function signInMethodLabel(mode: AuthMode | null): string | null {
  if (mode === 'oidc') return 'über Verzeichnisdienst angemeldet'
  if (mode === 'dev') return 'über Entwicklungsanmeldung angemeldet'
  return null
}

export default function SettingsPage() {
  const themeMode = useUiStore((s) => s.themeMode)
  const setThemeMode = useUiStore((s) => s.setThemeMode)
  const clearThemeMode = useUiStore((s) => s.clearThemeMode)
  const operatorDefault = useBrandingStore((s) => s.branding.defaultColorScheme)
  const user = useAuthStore((s) => s.user)
  const authMode = useAuthStore((s) => s.mode)
  const isSystemAdmin = user?.systemRole === 'SYSTEM_ADMIN'

  // The toggle shows what actually applies, which for someone who has never chosen is the
  // operator's default - not an empty selection they would have to interpret (#583).
  const hasOwnChoice = themeMode !== null
  const effectiveMode = resolveThemeMode(themeMode, operatorDefault)

  const signInMethod = signInMethodLabel(authMode)
  const accountMeta = [user?.email, signInMethod].filter(Boolean).join(' · ')

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      <Box sx={{ maxWidth: 640 }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, flexWrap: 'wrap' }}>
          <PageHeading title="Ihre Einstellungen" />
          <GlobalBadge />
        </Box>
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 0.5, mb: 3 }}>
          Gelten für Sie persönlich in allen Spaces.
        </Typography>

        {/* Mockup 2c: the profile block - display only; editing name, language or picture
            needs backend support that does not exist yet (#788, Abgrenzung). */}
        {user && (
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, mb: 4 }}>
            <Avatar
              sx={{ width: 56, height: 56, bgcolor: 'primary.main', fontSize: 20, fontWeight: 600 }}
            >
              {(user.displayName ?? user.email ?? '?')[0].toUpperCase()}
            </Avatar>
            <Box sx={{ minWidth: 0 }}>
              <Typography noWrap sx={{ fontSize: 15, fontWeight: 600 }}>
                {user.displayName ?? user.email ?? 'Benutzer'}
              </Typography>
              {accountMeta && (
                <Typography noWrap sx={{ fontSize: 12.5, color: 'text.secondary' }}>
                  {accountMeta}
                </Typography>
              )}
            </Box>
          </Box>
        )}

        <SectionHead>Darstellung</SectionHead>
        <Typography sx={{ fontSize: 13.5, color: 'text.secondary', mb: 2 }}>
          {hasOwnChoice
            ? 'Ihre eigene Wahl gilt und bleibt von der Vorgabe Ihres Hauses unberührt.'
            : 'Aktuell gilt die Vorgabe Ihres Hauses. Sobald Sie hier wählen, gilt Ihre Wahl.'}
        </Typography>
        <ToggleButtonGroup
          value={effectiveMode}
          exclusive
          onChange={(_e, value: ThemeMode | null) => {
            if (value !== null) setThemeMode(value)
          }}
          aria-label="Farbschema"
        >
          <ToggleButton value="light" aria-label="Helles Farbschema">
            <LightModeIcon sx={{ mr: 1 }} fontSize="small" />
            Hell
          </ToggleButton>
          <ToggleButton value="system" aria-label="Systemvorgabe">
            <SettingsBrightnessIcon sx={{ mr: 1 }} fontSize="small" />
            System
          </ToggleButton>
          <ToggleButton value="dark" aria-label="Dunkles Farbschema">
            <DarkModeIcon sx={{ mr: 1 }} fontSize="small" />
            Dunkel
          </ToggleButton>
        </ToggleButtonGroup>
        {hasOwnChoice && (
          <Box sx={{ mt: 2 }}>
            <Button size="small" onClick={clearThemeMode}>
              Vorgabe des Hauses übernehmen
            </Button>
          </Box>
        )}

        <Box sx={{ mt: 5 }}>
          <SectionHead>Erscheinungsbild des Hauses</SectionHead>
          <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
            Logo, Produktname, Akzentfarbe und das Standard-Farbschema stellt Ihr Haus zentral ein{' '}
            {isSystemAdmin ? (
              <>
                — als Systemadministration unter{' '}
                <Link component={RouterLink} to="/admin/branding">
                  Branding
                </Link>
                .
              </>
            ) : (
              '— wenden Sie sich dafür an Ihre Systemadministration.'
            )}
          </Typography>
        </Box>
      </Box>
    </Box>
  )
}
