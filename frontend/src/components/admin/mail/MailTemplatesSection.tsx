import { useEffect } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Chip from '@mui/material/Chip'
import List from '@mui/material/List'
import ListItem from '@mui/material/ListItem'
import ListItemButton from '@mui/material/ListItemButton'
import Skeleton from '@mui/material/Skeleton'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import visuallyHidden from '@mui/utils/visuallyHidden'
import { useMailStore } from '../../../stores/mailStore'
import SectionHead from '../../SectionHead'
import { radius } from '../../../theme/tokens'
import MailTemplateEditor from './MailTemplateEditor'

/** Wofür eine Vorlage verwendet wird — der Zweck in einem Satz, in der Sprache der Verwaltung. */
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
  TEST_MAIL: 'Die Nachricht, die „Testmail an mich senden“ verschickt.',
}

/**
 * Liste der zwölf Vorlagen und der Editor der ausgewählten (#1542). Die Registry ist
 * geschlossen: Eine Vorlage lässt sich überschreiben und zurücksetzen, nicht anlegen und nicht
 * löschen — deshalb gibt es hier keine „Neu“-Schaltfläche.
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
        {/* Eine senkrechte Linie trennt die Auswahl vom Inhalt, kein Rahmen ringsum (#1608) -
            dieselbe Trennung, die die Bereichsnavigation der Seite links schon nutzt. */}
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
                    <Chip
                      size="small"
                      variant="outlined"
                      label={summary.source === 'DATABASE' ? 'angepasst' : 'Standard'}
                    />
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
            // Der Schlüssel trägt den Stand: Nach Speichern oder Zurücksetzen ist die Antwort
            // des Servers die Quelle des Entwurfs, und ein frisch montierter Editor übernimmt sie,
            // ohne den Entwurf aus einem Effekt heraus nachzuziehen.
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
