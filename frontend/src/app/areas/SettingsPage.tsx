import Avatar from '@mui/material/Avatar'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import ToggleButton from '@mui/material/ToggleButton'
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup'
import DarkModeIcon from '@mui/icons-material/DarkMode'
import LightModeIcon from '@mui/icons-material/LightMode'
import SettingsBrightnessIcon from '@mui/icons-material/SettingsBrightness'
import TuneOutlinedIcon from '@mui/icons-material/TuneOutlined'
import { Navigate, useNavigate, useParams } from 'react-router'
import { useAuthStore } from '@/shared/session/authStore'
import { useFeatureEnabled } from '@/shared/session/useFeatureEnabled'
import { PASSWORD_ROUTE, SETTINGS_ROUTE } from '@/shared/routes'
import { useUiStore } from '@/shared/stores/uiStore'
import type { ThemeMode } from '@/shared/stores/uiStore'
import GlobalBadge from '@/shared/ui/GlobalBadge'
import AreaPageHeader from '@/shared/ui/AreaPageHeader'
import { userInitial } from '@/shared/utils/userInitial'
import SectionHead from '@/shared/ui/SectionHead'
import { TextAction } from '@/shared/ui/DetailSection'
import ValueRow from '@/shared/ui/ValueRow'
import { contentWidth } from '@/shared/theme/tokens'
import AreaTabs from '@/shared/ui/AreaTabs'
import { OwnExternalAccessTokensSection } from '@/features/external-access'
import { MyGroupsSection } from '@/features/group'
import { ConnectedAccountsSection } from '@/features/connection'
import { MyDataSection } from '@/features/compliance'
import { MyChatAttachmentsSection, TranscriptionVocabularySection } from '@/features/chat'

export type SettingsTab = 'general' | 'groups' | 'tokens' | 'accounts' | 'uploads' | 'data'

const tabs: Array<{ value: SettingsTab; label: string }> = [
  { value: 'general', label: 'Allgemein' },
  { value: 'groups', label: 'Meine Gruppen' },
  { value: 'tokens', label: 'Zugangstokens' },
  { value: 'accounts', label: 'Verbundene Konten' },
  { value: 'uploads', label: 'Meine Uploads' },
  { value: 'data', label: 'Meine Daten' },
]

/**
 * The tabs the installation offers: switched off (ADR-0044), the access tokens and the chat
 * attachments have none.
 */
function offeredTabs(
  externalAccess: boolean,
  chatUpload: boolean,
): Array<{ value: SettingsTab; label: string }> {
  return tabs.filter(
    (entry) =>
      (externalAccess || entry.value !== 'tokens') && (chatUpload || entry.value !== 'uploads'),
  )
}

function isSettingsTab(
  value: string | undefined,
  offered: Array<{ value: SettingsTab; label: string }>,
): value is SettingsTab {
  return offered.some((entry) => entry.value === value)
}

/**
 * The personal settings, with the own access tokens, the groups the account is responsible for,
 * the connected accounts and the person's own data (self-disclosure) as further areas.
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
  const navigate = useNavigate()
  const user = useAuthStore((s) => s.user)
  const sessionKind = useAuthStore((s) => s.sessionKind)
  const offered = offeredTabs(useFeatureEnabled('externalAccess'), useFeatureEnabled('chatUpload'))
  // ADR-0033, Entscheidung 11: the creation reason belongs to the person's own self-disclosure -
  // an account of an identity provider has none, and the block then stays away entirely.
  const createdReason = user?.createdReason?.trim() || null

  // A missing or blank displayName makes the e-mail the name line - repeating it in the
  // meta line would show it twice.
  const displayName = user?.displayName?.trim() || null
  const accountName = displayName ?? user?.email ?? 'Benutzer'
  const accountMeta = displayName ? user?.email : null

  if (tab !== undefined && !isSettingsTab(tab, offered)) {
    return <Navigate to={SETTINGS_ROUTE} replace />
  }
  const activeTab: SettingsTab = isSettingsTab(tab, offered) ? tab : 'general'

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      {/* one width for every area, so the tab row scrolls alike in all of them */}
      <Box sx={{ maxWidth: contentWidth.areaContent }}>
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
            ) : value === 'data' ? (
              <MyDataSection />
            ) : value === 'uploads' ? (
              <MyChatAttachmentsSection />
            ) : value === 'accounts' ? (
              <ConnectedAccountsSection />
            ) : value === 'groups' ? (
              <MyGroupsSection />
            ) : (
              <>
                {/* The profile block - display only; editing name, language or picture
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

                {/* An identity provider's account has neither a reason nor a password here. */}
                {(createdReason || sessionKind === 'local') && (
                  <Box component="section" sx={{ mb: 5 }}>
                    <SectionHead>Ihr Konto</SectionHead>
                    {createdReason && (
                      <ValueRow
                        title="Anlass des Kontos"
                        headingLevel="h3"
                        value={
                          <>
                            {createdReason}
                            <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 0.5 }}>
                              So hat Ihre Systemverwaltung den dienstlichen Anlass Ihres Kontos
                              festgehalten.
                            </Typography>
                          </>
                        }
                      />
                    )}
                    {sessionKind === 'local' && (
                      <ValueRow
                        title="Passwort"
                        headingLevel="h3"
                        value={
                          <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
                            Gilt nur für diese Installation.
                          </Typography>
                        }
                        action={
                          // the password page returns to where it was opened from
                          <TextAction
                            context="Passwort"
                            onClick={() =>
                              navigate(PASSWORD_ROUTE, { state: { from: SETTINGS_ROUTE } })
                            }
                          >
                            Ändern
                          </TextAction>
                        }
                      />
                    )}
                  </Box>
                )}

                <SectionHead>Darstellung</SectionHead>
                <Typography sx={{ fontSize: 13.5, color: 'text.secondary', mb: 2 }}>
                  Gilt in diesem Browser.
                </Typography>
                <ToggleButtonGroup
                  value={themeMode}
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

                <TranscriptionVocabularySection />
              </>
            )
          }
        </AreaTabs>
      </Box>
    </Box>
  )
}
