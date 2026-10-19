import Avatar from '@mui/material/Avatar'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import ToggleButton from '@mui/material/ToggleButton'
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup'
import DarkModeIcon from '@mui/icons-material/DarkMode'
import LightModeIcon from '@mui/icons-material/LightMode'
import SettingsBrightnessIcon from '@mui/icons-material/SettingsBrightness'
import TuneOutlinedIcon from '@mui/icons-material/TuneOutlined'
import Button from '@mui/material/Button'
import Link from '@mui/material/Link'
import { Link as RouterLink, Navigate, useParams } from 'react-router'
import { useAuthStore } from '@/shared/session/authStore'
import { useFeatureEnabled } from '@/shared/session/useFeatureEnabled'
import type { AuthMode } from '@/shared/session/types'
import { PASSWORD_ROUTE, SETTINGS_ROUTE } from '@/shared/routes'
import { useUiStore } from '@/shared/stores/uiStore'
import type { ThemeMode } from '@/shared/stores/uiStore'
import { useBrandingStore } from '@/shared/branding/brandingStore'
import { resolveThemeMode } from '@/shared/theme/colorScheme'
import GlobalBadge from '@/shared/ui/GlobalBadge'
import AreaPageHeader from '@/shared/ui/AreaPageHeader'
import { userInitial } from '@/shared/utils/userInitial'
import SectionHead from '@/shared/ui/SectionHead'
import { contentWidth } from '@/shared/theme/tokens'
import AreaTabs from '@/shared/ui/AreaTabs'
import { OwnExternalAccessTokensSection } from '@/features/external-access'
import { MyGroupsSection } from '@/features/group'
import { ConnectedAccountsSection } from '@/features/connection'

export type SettingsTab = 'general' | 'groups' | 'tokens' | 'accounts'

const tabs: Array<{ value: SettingsTab; label: string }> = [
  { value: 'general', label: 'Allgemein' },
  { value: 'groups', label: 'Meine Gruppen' },
  { value: 'tokens', label: 'Zugangstokens' },
  { value: 'accounts', label: 'Verbundene Konten' },
]

/** The tabs the installation offers: switched off (ADR-0044), the access tokens have none. */
function offeredTabs(externalAccess: boolean): Array<{ value: SettingsTab; label: string }> {
  return externalAccess ? tabs : tabs.filter((entry) => entry.value !== 'tokens')
}

function isSettingsTab(
  value: string | undefined,
  offered: Array<{ value: SettingsTab; label: string }>,
): value is SettingsTab {
  return offered.some((entry) => entry.value === value)
}

/** Mockup 2c: how the account signed in, next to the address - never a technical mode name. */
function signInMethodLabel(mode: AuthMode | null): string | null {
  if (mode === 'oidc') return 'über Verzeichnisdienst angemeldet'
  if (mode === 'dev') return 'über Entwicklungsanmeldung angemeldet'
  return null
}

/**
 * The personal settings, with the own access tokens and the groups the account is
 * responsible for as further areas.
 *
 * The areas are routes, so that a link to the token management lands there and a
 * reload keeps it. `/settings` without an area stays the entry point and shows „Allgemein“; a
 * typo in the path, by contrast, is not silently read that way. If the installation has
 * switched off external access, the area of the access tokens does not exist.
 */
export default function SettingsPage() {
  const { tab } = useParams()
  const themeMode = useUiStore((s) => s.themeMode)
  const setThemeMode = useUiStore((s) => s.setThemeMode)
  const clearThemeMode = useUiStore((s) => s.clearThemeMode)
  const operatorDefault = useBrandingStore((s) => s.branding.defaultColorScheme)
  const user = useAuthStore((s) => s.user)
  const authMode = useAuthStore((s) => s.mode)
  const sessionKind = useAuthStore((s) => s.sessionKind)
  const isSystemAdmin = user?.systemRole === 'SYSTEM_ADMIN'
  const offered = offeredTabs(useFeatureEnabled('externalAccess'))
  // ADR-0033, Entscheidung 11: the creation reason belongs to the person's own self-disclosure -
  // an account of an identity provider has none, and the block then stays away entirely.
  const createdReason = user?.createdReason?.trim() || null

  // The toggle shows what actually applies, which for someone who has never chosen is the
  // operator's default - not an empty selection they would have to interpret.
  const hasOwnChoice = themeMode !== null
  const effectiveMode = resolveThemeMode(themeMode, operatorDefault)

  const signInMethod = signInMethodLabel(authMode)
  // A missing or blank displayName makes the e-mail the name line - repeating it in the
  // meta line would show it twice.
  const displayName = user?.displayName?.trim() || null
  const accountName = displayName ?? user?.email ?? 'Benutzer'
  const accountMeta = [displayName ? user?.email : null, signInMethod].filter(Boolean).join(' · ')

  if (tab !== undefined && !isSettingsTab(tab, offered)) {
    return <Navigate to={SETTINGS_ROUTE} replace />
  }
  const activeTab: SettingsTab = isSettingsTab(tab, offered) ? tab : 'general'

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      <Box sx={{ maxWidth: activeTab === 'general' ? 640 : contentWidth.areaContent }}>
        <AreaPageHeader
          icon={TuneOutlinedIcon}
          title="Ihre Einstellungen"
          meta={<GlobalBadge />}
          description="Gelten für Sie persönlich in allen Spaces."
        />

        <AreaTabs
          tabs={offered}
          value={activeTab}
          href={(value) => `/settings/${value}`}
          label="Bereiche Ihrer Einstellungen"
          idPrefix="settings"
        >
          {(value) =>
            value === 'tokens' ? (
              <OwnExternalAccessTokensSection />
            ) : value === 'accounts' ? (
              <ConnectedAccountsSection />
            ) : value === 'groups' ? (
              <MyGroupsSection />
            ) : (
              <>
                {/* Mockup 2c: the profile block - display only; editing name, language or picture
            needs backend support that does not exist yet (Abgrenzung). */}
                {user && (
                  <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, mb: 4 }}>
                    {/* Decorative: the name stands right next to it (WCAG 1.1.1). */}
                    <Avatar
                      aria-hidden="true"
                      sx={{
                        width: 56,
                        height: 56,
                        bgcolor: 'primary.main',
                        fontSize: 20,
                        fontWeight: 600,
                      }}
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
                          So hat Ihre Systemverwaltung den dienstlichen Anlass Ihres Kontos
                          festgehalten.
                        </Typography>
                      </Box>
                    )}
                    {sessionKind === 'local' ? (
                      <Box>
                        <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
                          Ihr Passwort gilt nur für diese Installation. Nach einer Änderung bleiben
                          Sie hier angemeldet; Ihre übrigen Sitzungen werden beendet.
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
                          Ihr Passwort verwaltet der Identitätsanbieter, über den Sie sich
                          angemeldet haben — ändern Sie es dort.
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
                  {/* Named by their visible text (WCAG 2.5.3); the group supplies "Farbschema". */}
                  <ToggleButton value="light">
                    <LightModeIcon sx={{ mr: 1 }} fontSize="small" />
                    Hell
                  </ToggleButton>
                  <ToggleButton value="system">
                    <SettingsBrightnessIcon sx={{ mr: 1 }} fontSize="small" />
                    System
                  </ToggleButton>
                  <ToggleButton value="dark">
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
                    Logo, Produktname, Akzentfarbe und das Standard-Farbschema stellt Ihr Haus
                    zentral ein{' '}
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
              </>
            )
          }
        </AreaTabs>
      </Box>
    </Box>
  )
}
