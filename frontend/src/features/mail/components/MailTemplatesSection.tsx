import { useEffect } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import List from '@mui/material/List'
import ListItem from '@mui/material/ListItem'
import ListItemButton from '@mui/material/ListItemButton'
import Skeleton from '@mui/material/Skeleton'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import visuallyHidden from '@mui/utils/visuallyHidden'
import { useMailStore } from '../stores/mailStore'
import SectionHead from '@/shared/ui/SectionHead'
import MetaBadge from '@/shared/ui/MetaBadge'
import { radius } from '@/shared/theme/tokens'
import MailTemplateEditor from './MailTemplateEditor'

/** What a template is used for — the purpose in one sentence, in the administration's language. */
const templatePurposes: Record<string, string> = {
  LOCAL_ACCOUNT_INVITATION: 'Geht an eine eingeladene Person, damit sie ihr Passwort festlegt.',
  PASSWORD_RESET: 'Antwort auf „Passwort vergessen“ auf der Anmeldeseite.',
  ADMIN_PASSWORD_RESET: 'Meldet, dass die Systemverwaltung das Passwort zurückgesetzt hat.',
  REGISTRATION_VERIFICATION: 'Bestätigt die Adresse bei der Selbstregistrierung.',
  ACCOUNT_LOCKED: 'Teilt eine Sperre durch die Verwaltung oder wegen Inaktivität mit.',
  ACCOUNT_UNLOCKED: 'Teilt mit, dass der Zugang wieder benutzbar ist.',
  ACCOUNT_EXPIRING: 'Warnt 14 Tage vor dem Ablauf eines befristeten Zugangs.',
  ACCOUNT_HANDOVER_REQUESTED: 'Stößt die Übergabe an eine Anbieteridentität an.',
  ACCOUNT_HANDED_OVER: 'Meldet die abgeschlossene Übergabe an eine Anbieteridentität.',
  BOOTSTRAP_ACCOUNT_USED: 'Meldet der Systemverwaltung die Nutzung des Notanker-Kontos.',
  ADMIN_REVIEW_REMINDER: 'Quartalsweise Wiedervorlage zur Prüfung der Zugänge.',
  SELF_DISCLOSURE_STARTED: 'Meldet der Person den Start einer Selbstauskunft ihrer Daten.',
  TEST_MAIL: 'Die Nachricht, die „Testmail an mich senden“ verschickt.',
}

/**
 * List of the twelve templates and the editor of the selected one. The registry is
 * closed: a template can be overridden and reset, not created and not
 * deleted — that is why there is no „Neu“ button here.
 */
export default function MailTemplatesSection() {
  const templates = useMailStore((s) => s.templates)
  const isLoading = useMailStore((s) => s.isLoadingTemplates)
  const listError = useMailStore((s) => s.templatesError)
  const template = useMailStore((s) => s.template)
  const isLoadingTemplate = useMailStore((s) => s.isLoadingTemplate)
  const loadTemplates = useMailStore((s) => s.loadTemplates)
  const openTemplate = useMailStore((s) => s.openTemplate)

  useEffect(() => {
    void loadTemplates()
  }, [loadTemplates])

  if (isLoading && templates.length === 0) {
    return (
      <Box aria-busy="true">
        <span style={visuallyHidden}>Die Vorlagen werden geladen …</span>
        <Skeleton variant="rounded" height={420} />
      </Box>
    )
  }

  return (
    <Box>
      {listError && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {listError}
        </Alert>
      )}

      <Stack direction={{ xs: 'column', md: 'row' }} spacing={3} sx={{ alignItems: 'flex-start' }}>
        {/* A vertical line separates the selection from the content, no frame all around -
           the same separation the page's area navigation on the left already uses. */}
        <Box
          component="nav"
          aria-label="Vorlagen"
          sx={{
            width: { xs: '100%', md: 320 },
            flex: 'none',
            pr: { md: 2 },
            borderRight: { md: 1 },
            borderBottom: { xs: 1, md: 0 },
            borderColor: 'divider',
            pb: { xs: 2, md: 0 },
          }}
        >
          <SectionHead id="mail-template-list-head">Vorlagen</SectionHead>
          <List disablePadding aria-labelledby="mail-template-list-head">
            {templates.map((summary) => (
              <ListItem key={summary.key} disablePadding>
                <ListItemButton
                  selected={template?.key === summary.key}
                  onClick={() => void openTemplate(summary.key)}
                  sx={{ alignItems: 'flex-start', flexDirection: 'column', gap: 0.25, py: 1 }}
                >
                  <Box
                    sx={{
                      display: 'flex',
                      alignItems: 'center',
                      gap: 1,
                      flexWrap: 'wrap',
                      width: '100%',
                    }}
                  >
                    <Typography sx={{ fontSize: 13.5, fontWeight: 500 }}>
                      {summary.label}
                    </Typography>
                    <MetaBadge>
                      {summary.source === 'DATABASE' ? 'angepasst' : 'Standard'}
                    </MetaBadge>
                  </Box>
                  <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>
                    {templatePurposes[summary.key] ?? summary.subject}
                  </Typography>
                </ListItemButton>
              </ListItem>
            ))}
          </List>
        </Box>

        <Box sx={{ flexGrow: 1, minWidth: 0, width: '100%' }}>
          {isLoadingTemplate && (
            <Box aria-busy="true">
              <span style={visuallyHidden}>Die Vorlage wird geladen …</span>
              <Skeleton variant="rounded" height={420} />
            </Box>
          )}
          {!isLoadingTemplate && !template && (
            <Box
              sx={{
                border: 1,
                borderStyle: 'dashed',
                borderColor: 'divider',
                borderRadius: `${radius.md}px`,
                p: 3,
                textAlign: 'center',
              }}
            >
              <Typography sx={{ fontSize: 13.5, fontWeight: 500 }}>
                Keine Vorlage ausgewählt.
              </Typography>
              <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 0.5 }}>
                Wählen Sie links eine Vorlage, um Betreff und Wortlaut anzupassen.
              </Typography>
            </Box>
          )}
          {!isLoadingTemplate && template && (
            // The key carries the state: after saving or resetting, the server's response
            // is the source of the draft, and a freshly mounted editor adopts it
            // without catching up the draft from an effect.
            <MailTemplateEditor
              key={`${template.key}-${template.source}-${template.updatedAt ?? 'default'}`}
              template={template}
            />
          )}
        </Box>
      </Stack>
    </Box>
  )
}
