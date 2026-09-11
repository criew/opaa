import { useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import Paper from '@mui/material/Paper'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import type { MailSendResultResponse, MailTemplateResponse } from '../../../types/api'
import { useMailStore } from '../../../stores/mailStore'
import { notify } from '../../../stores/notificationStore'
import { useMailTemplatePreview } from '../../../hooks/useMailTemplatePreview'
import { radius } from '../../../theme/tokens'
import SectionHead from '../../SectionHead'
import MailTemplatePreviewPane from './MailTemplatePreviewPane'
import { mailSendResultMessage, mailSendResultSeverity } from './mailStatus'

/** Was ein Platzhalter in der fertigen Nachricht einsetzt — Wortlaut wie im Backend-Beispielsatz. */
const placeholderDescriptions: Record<string, string> = {
  productName: 'Produktname aus dem Branding',
  displayName: 'Anzeigename der empfangenden Person',
  actionUrl: 'Link auf die Handlung (Passwort festlegen, Adresse bestätigen …)',
  expiresAtHuman: 'Restlaufzeit des Links in Worten, etwa „noch 24 Stunden“',
  reason: 'Grund der Sperre im Klartext',
  count: 'Anzahl der Zugänge, die auf eine Prüfung warten',
  occurredAtHuman: 'Zeitpunkt des Vorgangs in Worten',
}

interface MailTemplateEditorProps {
  template: MailTemplateResponse
}

/**
 * Der Editor einer Vorlage (#1542): Betreff und Textfassung mit Platzhalter-Chips, entprellte
 * Vorschau, Gegenüberstellung mit dem Standard, Zurücksetzen und Testversand.
 *
 * Die HTML-Fassung wird in Phase 1 nicht bearbeitet — sie entsteht aus dem Text im gebrandeten
 * Rahmen des Backends und ist in der Vorschau zu sehen.
 */
export default function MailTemplateEditor({ template }: MailTemplateEditorProps) {
  const isSaving = useMailStore((s) => s.isSavingTemplate)
  const error = useMailStore((s) => s.templateError)
  const saveTemplate = useMailStore((s) => s.saveTemplate)
  const restoreTemplateDefault = useMailStore((s) => s.restoreTemplateDefault)
  const sendTemplateTestMail = useMailStore((s) => s.sendTemplateTestMail)

  const [subject, setSubject] = useState(template.subject)
  const [bodyPlain, setBodyPlain] = useState(template.bodyPlain)
  const [showComparison, setShowComparison] = useState(false)
  const [testResult, setTestResult] = useState<MailSendResultResponse | null>(null)
  const subjectRef = useRef<HTMLInputElement>(null)
  const bodyRef = useRef<HTMLTextAreaElement>(null)
  /** Which field a placeholder chip inserts into - the one that had the caret last. */
  const lastFocused = useRef<'subject' | 'body'>('body')

  const preview = useMailTemplatePreview(template.key, subject, bodyPlain)
  const isDirty = subject !== template.subject || bodyPlain !== template.bodyPlain

  function insertPlaceholder(name: string) {
    const token = `{{${name}}}`
    if (lastFocused.current === 'subject') {
      const field = subjectRef.current
      const at = field?.selectionStart ?? subject.length
      setSubject(subject.slice(0, at) + token + subject.slice(field?.selectionEnd ?? at))
      return
    }
    const field = bodyRef.current
    const at = field?.selectionStart ?? bodyPlain.length
    setBodyPlain(bodyPlain.slice(0, at) + token + bodyPlain.slice(field?.selectionEnd ?? at))
  }

  async function handleSave() {
    try {
      await saveTemplate(template.key, { subject, bodyPlain })
      notify(`„${template.label}“ wurde gespeichert.`, 'success')
    } catch {
      // the store holds the backend's field error; the alert below renders it
    }
  }

  async function handleReset() {
    if (
      !window.confirm(
        `„${template.label}“ auf den ausgelieferten Standard zurücksetzen? Die angepasste Fassung` +
          ' geht dabei verloren.',
      )
    ) {
      return
    }
    try {
      await restoreTemplateDefault(template.key)
      notify(`„${template.label}“ steht wieder auf dem Standard.`, 'success')
    } catch {
      // dito
    }
  }

  async function handleTest() {
    setTestResult(null)
    try {
      setTestResult(await sendTemplateTestMail(template.key))
    } catch (err) {
      notify(
        err instanceof Error ? err.message : 'Der Testversand konnte nicht angestoßen werden.',
        'error',
      )
    }
  }

  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 1.5, flexWrap: 'wrap', mb: 2 }}>
        <Typography component="h2" sx={{ fontSize: 16, fontWeight: 600 }}>
          {template.label}
        </Typography>
        <Chip
          size="small"
          variant="outlined"
          label={template.source === 'DATABASE' ? 'angepasst' : 'Standard'}
        />
        <Typography
          component="code"
          sx={{ fontFamily: 'monospace', fontSize: 12, color: 'text.secondary' }}
        >
          {template.key}
        </Typography>
      </Box>

      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}

      <Paper variant="outlined" sx={{ p: 2.5, mb: 2.5 }}>
        <Stack spacing={2.5}>
          <TextField
            label="Betreff"
            value={subject}
            inputRef={subjectRef}
            onFocus={() => (lastFocused.current = 'subject')}
            onChange={(e) => setSubject(e.target.value)}
            slotProps={{ htmlInput: { maxLength: 300 } }}
            fullWidth
          />
          <TextField
            label="Textfassung"
            value={bodyPlain}
            inputRef={bodyRef}
            onFocus={() => (lastFocused.current = 'body')}
            onChange={(e) => setBodyPlain(e.target.value)}
            slotProps={{ htmlInput: { maxLength: 20000 } }}
            helperText="Die HTML-Fassung entsteht daraus im gebrandeten Rahmen und wird hier nicht bearbeitet."
            multiline
            minRows={10}
            fullWidth
          />

          <Box component="section" aria-labelledby="mail-placeholders-head">
            <SectionHead id="mail-placeholders-head" underline={false}>
              Zulässige Platzhalter
            </SectionHead>
            <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 0.5, mb: 1 }}>
              Ein Klick fügt den Platzhalter an der Schreibmarke ein. Ein anderer Name wird beim
              Speichern abgewiesen — eine Vorlage, die erst beim Versand scheitert, scheitert,
              während jemand auf seine Einladung wartet.
            </Typography>
            <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', gap: 1 }}>
              {template.placeholders.map((name) => (
                <Tooltip key={name} title={placeholderDescriptions[name] ?? 'Platzhalter'}>
                  <Chip
                    label={`{{${name}}}`}
                    size="small"
                    variant="outlined"
                    onClick={() => insertPlaceholder(name)}
                    aria-label={`Platzhalter ${name} einfügen`}
                    sx={{ fontFamily: 'monospace' }}
                  />
                </Tooltip>
              ))}
            </Stack>
          </Box>

          <Stack direction="row" spacing={1.5} sx={{ flexWrap: 'wrap', gap: 1 }}>
            <Button
              variant="contained"
              onClick={() => void handleSave()}
              disabled={isSaving || !isDirty}
            >
              Speichern
            </Button>
            <Button
              onClick={() => {
                setSubject(template.subject)
                setBodyPlain(template.bodyPlain)
              }}
              disabled={isSaving || !isDirty}
            >
              Änderungen verwerfen
            </Button>
            <Button variant="outlined" onClick={() => setShowComparison(!showComparison)}>
              {showComparison ? 'Vergleich ausblenden' : 'Mit Standard vergleichen'}
            </Button>
            <Button variant="outlined" onClick={() => void handleTest()} disabled={isSaving}>
              Testmail senden
            </Button>
            <Button
              color="error"
              onClick={() => void handleReset()}
              disabled={isSaving || template.source !== 'DATABASE'}
            >
              Auf Standard zurücksetzen
            </Button>
          </Stack>

          {testResult && (
            <Alert severity={mailSendResultSeverity(testResult)}>
              {mailSendResultMessage(testResult)}
            </Alert>
          )}
        </Stack>
      </Paper>

      {showComparison && (
        <Paper variant="outlined" sx={{ p: 2.5, mb: 2.5 }}>
          <Box component="section" aria-labelledby="mail-compare-head">
            <SectionHead id="mail-compare-head">Gegenüberstellung mit dem Standard</SectionHead>
            <Stack direction={{ xs: 'column', md: 'row' }} spacing={2}>
              <ComparisonColumn
                heading="Ausgelieferter Standard"
                subject={template.defaultSubject}
                bodyPlain={template.defaultBodyPlain}
              />
              <ComparisonColumn heading="Ihre Fassung" subject={subject} bodyPlain={bodyPlain} />
            </Stack>
          </Box>
        </Paper>
      )}

      <Paper variant="outlined" sx={{ p: 2.5 }}>
        <MailTemplatePreviewPane
          preview={preview.preview}
          error={preview.error}
          isLoading={preview.isLoading}
        />
      </Paper>
    </Box>
  )
}

function ComparisonColumn({
  heading,
  subject,
  bodyPlain,
}: {
  heading: string
  subject: string
  bodyPlain: string
}) {
  return (
    <Box sx={{ flex: 1, minWidth: 0 }}>
      <Typography component="h3" sx={{ fontSize: 13, fontWeight: 600, mb: 1 }}>
        {heading}
      </Typography>
      <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>Betreff</Typography>
      <Typography sx={{ fontSize: 13.5, mb: 1.5, wordBreak: 'break-word' }}>{subject}</Typography>
      <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>Textfassung</Typography>
      <Typography
        component="pre"
        sx={{
          fontFamily: 'monospace',
          fontSize: 12,
          whiteSpace: 'pre-wrap',
          wordBreak: 'break-word',
          border: 1,
          borderColor: 'divider',
          borderRadius: `${radius.sm}px`,
          p: 1.25,
          mt: 0.5,
          mb: 0,
        }}
      >
        {bodyPlain}
      </Typography>
    </Box>
  )
}
