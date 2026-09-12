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
import { PASSWORD_ROUTE, SETTINGS_ROUTE } from '../routes'
import { useUiStore } from '../stores/uiStore'
import type { ThemeMode } from '../stores/uiStore'
import { useBrandingStore } from '../stores/brandingStore'
import { resolveThemeMode } from '../theme/colorScheme'
import GlobalBadge from '../components/GlobalBadge'
import GlobalScopeNote from '../components/GlobalScopeNote'
import PageHeading from '../components/a11y/PageHeading'
import { userInitial } from '../utils/userInitial'
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
  const sessionKind = useAuthStore((s) => s.sessionKind)
  const isSystemAdmin = user?.systemRole === 'SYSTEM_ADMIN'
  // ADR-0033, Entscheidung 11: the creation reason belongs to the person's own self-disclosure -
  // an account of an identity provider has none, and the block then stays away entirely.
  const createdReason = user?.createdReason?.trim() || null

  // The toggle shows what actually applies, which for someone who has never chosen is the
  // operator's default - not an empty selection they would have to interpret (#583).
  const hasOwnChoice = themeMode !== null
  const effectiveMode = resolveThemeMode(themeMode, operatorDefault)

  const signInMethod = signInMethodLabel(authMode)
  // A missing or blank displayName makes the e-mail the name line - repeating it in the
  // meta line would show it twice (#800, review #795 finding 1).
  const displayName = user?.displayName?.trim() || null
  const accountName = displayName ?? user?.email ?? 'Benutzer'
  const accountMeta = [displayName ? user?.email : null, signInMethod].filter(Boolean).join(' · ')

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      <Box sx={{ maxWidth: 640 }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, flexWrap: 'wrap' }}>
          <PageHeading title="Ihre Einstellungen" />
          <GlobalBadge />
        </Box>
        <GlobalScopeNote sx={{ mt: 0.5, mb: 3 }}>
          Gelten für Sie persönlich in allen Spaces.
        </GlobalScopeNote>

        {/* Mockup 2c: the profile block - display only; editing name, language or picture
            needs backend support that does not exist yet (#788, Abgrenzung). */}
        {user && (
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, mb: 4 }}>
            {/* Decorative: the name stands right next to it (WCAG 1.1.1). */}
            <Avatar
              aria-hidden="true"
              sx={{ width: 56, height: 56, bgcolor: 'primary.main', fontSize: 20, fontWeight: 600 }}
            >
              {userInitial(user)}
            </Avatar>
            <Box sx={{ minWidth: 0 }}>
              <Typography noWrap sx={{ fontSize: 15, fontWeight: 600 }}>
                {accountName}
              </Typography>
              {accountMeta && (
                <Typography noWrap sx={{ fontSize: 12.5, color: 'text.secondary' }}>
                  {accountMeta}
                </Typography>
              )}
            </Box>
          </Box>
        )}

        {(createdReason || sessionKind !== null) && (
          <Box component="section" sx={{ mb: 5 }}>
            <SectionHead>Ihr Konto</SectionHead>
            {createdReason && (
              <Box sx={{ mb: sessionKind === null ? 0 : 2.5 }}>
                <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>
                  Anlass des Kontos
                </Typography>
                <Typography sx={{ fontSize: 13.5, mt: 0.25 }}>{createdReason}</Typography>
                <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 0.5 }}>
                  So hat Ihre Systemverwaltung den dienstlichen Anlass Ihres Kontos festgehalten.
                </Typography>
              </Box>
            )}
            {sessionKind === 'local' ? (
              <Box>
                <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
                  Ihr Passwort gilt nur für diese Installation. Nach einer Änderung bleiben Sie hier
                  angemeldet; Ihre übrigen Sitzungen werden beendet.
                </Typography>
                {/* The password page returns to where it was opened from; without the target a
                    voluntary change would end on the chat page (see redirectTargetOf). */}
                <Button
                  component={RouterLink}
                  to={PASSWORD_ROUTE}
                  state={{ from: SETTINGS_ROUTE }}
                  size="small"
                  variant="outlined"
                  sx={{ mt: 1.5 }}
                >
                  Passwort ändern
                </Button>
              </Box>
            ) : (
              sessionKind === 'oidc' && (
                <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
                  Ihr Passwort verwaltet der Identitätsanbieter, über den Sie sich angemeldet haben
                  — ändern Sie es dort.
                </Typography>
              )
            )}
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
