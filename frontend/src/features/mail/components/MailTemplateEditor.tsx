import { useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import Stack from '@mui/material/Stack'
import FormField from '@/shared/ui/form/FormField'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import type { MailSendResultResponse, MailTemplateResponse } from '@/shared/api/types'
import { useMailStore } from '../stores/mailStore'
import { confirmAction } from '@/shared/stores/confirmStore'
import { notify } from '@/shared/stores/notificationStore'
import { useMailTemplatePreview } from '../hooks/useMailTemplatePreview'
import { fontSize, radius, formWidth } from '@/shared/theme/tokens'
import PageSection from '@/shared/ui/PageSection'
import MetaBadge from '@/shared/ui/MetaBadge'
import SectionHead from '@/shared/ui/SectionHead'
import MailTemplatePreviewPane from './MailTemplatePreviewPane'
import { mailSendResultMessage, mailSendResultSeverity } from './mailStatus'
import { MAIL_CONFIRM_PURPOSE } from './mailRecentLogin'
import { useRecentLoginGate } from '@/shared/session/useRecentLoginGate'

/** What a placeholder inserts into the finished message — wording as in the backend's sample. */
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
 * The editor of a template: subject and text version with placeholder chips, debounced
 * preview, comparison with the default, reset and test sending.
 *
 * The HTML version is not edited in phase 1 — it is created from the text in the backend's
 * branded frame and can be seen in the preview.
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
  const [isTesting, setIsTesting] = useState(false)
  const [refusal, setRefusal] = useState<string | null>(null)
  const gate = useRecentLoginGate(MAIL_CONFIRM_PURPOSE)
  const subjectRef = useRef<HTMLInputElement>(null)
  const bodyRef = useRef<HTMLTextAreaElement>(null)
  /** Which field a placeholder chip inserts into - the one that had the caret last. */
  const lastFocused = useRef<'subject' | 'body'>('body')

  const preview = useMailTemplatePreview(template.key, subject, bodyPlain)
  const isDirty = subject !== template.subject || bodyPlain !== template.bodyPlain
  // Mirrors the minLength of MailTemplateUpdateRequest: an empty field would come back as the
  // framework's own English validation message, and the preview would silently fall back to the
  // stored version instead of showing the emptiness.
  const isSubjectEmpty = subject.trim() === ''
  const isBodyEmpty = bodyPlain.trim() === ''
  const canSave = isDirty && !isSubjectEmpty && !isBodyEmpty

  /**
   * Inserts the placeholder at the caret of the field that had it last and hands the focus back
   * with the caret behind the inserted token - typing on after a chip click is the normal case,
   * and a chip that drops the caret makes the whole affordance slower than typing the braces.
   */
  function insertPlaceholder(name: string) {
    const token = `{{${name}}}`
    const intoSubject = lastFocused.current === 'subject'
    const field = intoSubject ? subjectRef.current : bodyRef.current
    const value = intoSubject ? subject : bodyPlain
    const start = field?.selectionStart ?? value.length
    const end = field?.selectionEnd ?? start
    const next = value.slice(0, start) + token + value.slice(end)
    if (intoSubject) {
      setSubject(next)
    } else {
      setBodyPlain(next)
    }
    const caret = start + token.length
    // After the state update: the controlled field only carries the new value on the next render.
    requestAnimationFrame(() => {
      field?.focus()
      field?.setSelectionRange(caret, caret)
    })
  }

  async function handleSave(afterConfirmation = false) {
    setRefusal(null)
    try {
      // bodyHtml unchanged: the PUT is a full replacement, so leaving the field out would quietly
      // drop an HTML override somebody stored through the API.
      await saveTemplate(template.key, { subject, bodyPlain, bodyHtml: template.bodyHtml })
      gate.settled()
      notify(`„${template.label}“ wurde gespeichert.`, 'success')
    } catch (err) {
      // a refusal for want of a recent sign-in opens the confirmation; any other refusal is the
      // store's backend field error, which the alert below renders
      const refused = gate.refusal(err, () => void handleSave(true), afterConfirmation)
      if (refused) setRefusal(refused)
    }
  }

  async function handleReset() {
    if (
      !(await confirmAction({
        question: `„${template.label}“ auf den ausgelieferten Standard zurücksetzen?`,
        consequence: 'Die angepasste Fassung geht dabei verloren.',
        confirmLabel: 'Zurücksetzen',
        tone: 'caution',
      }))
    ) {
      return
    }
    try {
      await restoreTemplateDefault(template.key)
      notify(`„${template.label}“ steht wieder auf dem Standard.`, 'success')
    } catch {
      // ditto
    }
  }

  async function handleTest() {
    setIsTesting(true)
    setTestResult(null)
    try {
      setTestResult(await sendTemplateTestMail(template.key))
    } catch (err) {
      notify(
        err instanceof Error ? err.message : 'Der Testversand konnte nicht angestoßen werden.',
        'error',
      )
    } finally {
      setIsTesting(false)
    }
  }

  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 1.5, flexWrap: 'wrap', mb: 2 }}>
        <Typography component="h2" sx={{ fontSize: fontSize.title, fontWeight: 600 }}>
          {template.label}
        </Typography>
        <MetaBadge>{template.source === 'DATABASE' ? 'angepasst' : 'Standard'}</MetaBadge>
        <Typography
          component="code"
          sx={{ fontFamily: 'monospace', fontSize: fontSize.meta, color: 'text.secondary' }}
        >
          {template.key}
        </Typography>
      </Box>

      {(refusal ?? error) && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {refusal ?? error}
        </Alert>
      )}

      <PageSection title="Wortlaut">
        <Stack spacing={2.5} sx={{ maxWidth: formWidth.column }}>
          <FormField
            label="Betreff"
            value={subject}
            inputRef={subjectRef}
            onFocus={() => (lastFocused.current = 'subject')}
            onChange={(e) => setSubject(e.target.value)}
            slotProps={{ htmlInput: { maxLength: 300 } }}
            error={isSubjectEmpty}
            helperText={isSubjectEmpty ? 'Betreff darf nicht leer sein.' : undefined}
            fullWidth
          />
          <FormField
            label="Textfassung"
            value={bodyPlain}
            inputRef={bodyRef}
            onFocus={() => (lastFocused.current = 'body')}
            onChange={(e) => setBodyPlain(e.target.value)}
            slotProps={{ htmlInput: { maxLength: 20000 } }}
            error={isBodyEmpty}
            helperText={
              isBodyEmpty
                ? 'Text darf nicht leer sein.'
                : 'Die HTML-Fassung entsteht daraus im gebrandeten Rahmen und wird hier nicht bearbeitet.'
            }
            multiline
            fullWidth
          />

          <Box component="section" aria-labelledby="mail-placeholders-head">
            <SectionHead id="mail-placeholders-head" underline={false}>
              Zulässige Platzhalter
            </SectionHead>
            <Typography sx={{ fontSize: fontSize.meta, color: 'text.secondary', mt: 0.5, mb: 1 }}>
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
              disabled={isSaving || !canSave}
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
            {/* The endpoint renders the STORED version - it takes nothing but the key. An
                unsaved draft would therefore produce a mail nobody wrote, so the button waits. */}
            <Button
              variant="outlined"
              onClick={() => void handleTest()}
              disabled={isSaving || isTesting || isDirty}
            >
              Testnachricht senden
            </Button>
            <Button
              color="error"
              onClick={() => void handleReset()}
              disabled={isSaving || template.source !== 'DATABASE'}
            >
              Auf Standard zurücksetzen
            </Button>
          </Stack>

          {isDirty && (
            <Typography sx={{ fontSize: fontSize.meta, color: 'text.secondary' }}>
              Zum Testen zuerst speichern: Die Testnachricht verschickt die gespeicherte Fassung, nicht
              den Entwurf im Editor.
            </Typography>
          )}

          {testResult && (
            <Alert severity={mailSendResultSeverity(testResult)}>
              {mailSendResultMessage(testResult)}
            </Alert>
          )}
        </Stack>
      </PageSection>

      {showComparison && (
        <PageSection title="Gegenüberstellung mit dem Standard">
          <Stack direction={{ xs: 'column', md: 'row' }} spacing={2}>
            <ComparisonColumn
              heading="Ausgelieferter Standard"
              subject={template.defaultSubject}
              bodyPlain={template.defaultBodyPlain}
            />
            <ComparisonColumn heading="Ihre Fassung" subject={subject} bodyPlain={bodyPlain} />
          </Stack>
        </PageSection>
      )}

      <MailTemplatePreviewPane
        preview={preview.preview}
        error={preview.error}
        isLoading={preview.isLoading}
      />
      {gate.dialog}
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
      <Typography component="h3" sx={{ fontSize: fontSize.text, fontWeight: 600, mb: 1 }}>
        {heading}
      </Typography>
      <Typography sx={{ fontSize: fontSize.meta, color: 'text.secondary' }}>Betreff</Typography>
      <Typography sx={{ fontSize: fontSize.text, mb: 1.5, wordBreak: 'break-word' }}>
        {subject}
      </Typography>
      <Typography sx={{ fontSize: fontSize.meta, color: 'text.secondary' }}>Textfassung</Typography>
      <Typography
        component="pre"
        sx={{
          fontFamily: 'monospace',
          fontSize: fontSize.meta,
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
