import { useRef, useState, type ChangeEvent } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import FileUploadOutlinedIcon from '@mui/icons-material/FileUploadOutlined'
import type { SettingsChange, SettingsDocument } from '@/shared/api/types'
import { apiFieldErrors } from '@/shared/api/apiErrorDetails'
import { useBrandingStore } from '@/shared/branding/brandingStore'
import { confirmAction } from '@/shared/stores/confirmStore'
import { notify } from '@/shared/stores/notificationStore'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import MetaBadge from '@/shared/ui/MetaBadge'
import PageSection from '@/shared/ui/PageSection'
import AdminList, { ListText } from '@/shared/ui/list/AdminList'
import { useRecentLoginGate } from '@/shared/session/useRecentLoginGate'
import { importSettings, previewSettingsImport } from '../api/settingsApi'
import { SETTINGS_IMPORT_CONFIRM_PURPOSE } from './settingsRecentLogin'
import {
  MAX_SETTINGS_FILE_BYTES,
  formatSettingValue,
  parseSettingsFile,
} from '../utils/settingsFile'
import { readingWidth } from '@/shared/theme/tokens'

interface Checked {
  fileName: string
  document: SettingsDocument
  changes: SettingsChange[]
}

interface Refusal {
  message: string
  details: string[]
}

function securityRelevant(changes: SettingsChange[]): number {
  return changes.filter((change) => change.securityRelevant).length
}

function refusalOf(err: unknown, fallback: string): Refusal {
  return {
    message: err instanceof Error && err.message ? err.message : fallback,
    details: apiFieldErrors(err).map((fieldError) => fieldError.message),
  }
}

/**
 * Takes over the settings of an exported file: the file is first checked against this installation
 * and its differences shown; only "Übernehmen" changes anything, every value or none.
 */
export default function SettingsImportSection() {
  const fileInput = useRef<HTMLInputElement | null>(null)
  const [checked, setChecked] = useState<Checked | null>(null)
  const [refusal, setRefusal] = useState<Refusal | null>(null)
  const [checking, setChecking] = useState(false)
  const [applying, setApplying] = useState(false)
  const gate = useRecentLoginGate(SETTINGS_IMPORT_CONFIRM_PURPOSE)

  async function handleFile(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0] ?? null
    event.target.value = ''
    setChecked(null)
    setRefusal(null)
    if (!file) return
    if (file.size > MAX_SETTINGS_FILE_BYTES) {
      setRefusal({ message: 'Die Datei ist größer als 256 KiB.', details: [] })
      return
    }
    const parsed = parseSettingsFile(await file.text())
    if ('error' in parsed) {
      setRefusal({ message: parsed.error, details: [] })
      return
    }
    setChecking(true)
    try {
      const preview = await previewSettingsImport(parsed.document)
      setChecked({ fileName: file.name, document: parsed.document, changes: preview.changes })
    } catch (err: unknown) {
      setRefusal(refusalOf(err, 'Die Datei konnte nicht geprüft werden.'))
    } finally {
      setChecking(false)
    }
  }

  async function handleApply() {
    if (!checked || applying) return
    const confirmed = await confirmAction({
      question: 'Einstellungen übernehmen?',
      consequence: `${checked.changes.length} ${
        checked.changes.length === 1 ? 'Angabe ändert sich' : 'Angaben ändern sich'
      } sofort für die ganze Installation${
        securityRelevant(checked.changes) > 0
          ? `, davon ${securityRelevant(checked.changes)} sicherheitsrelevant`
          : ''
      }. Jede Änderung steht im Revisionsprotokoll.`,
      confirmLabel: 'Übernehmen',
      tone: 'caution',
    })
    if (!confirmed) return
    await apply(checked.document)
  }

  async function apply(document: SettingsDocument, afterConfirmation = false) {
    setRefusal(null)
    setApplying(true)
    try {
      const result = await importSettings(document)
      gate.settled()
      setChecked(null)
      if (result.changes.some((change) => change.section === 'branding')) {
        void useBrandingStore.getState().loadBranding()
      }
      notify(
        result.changes.length === 0
          ? 'Die Einstellungen stimmten bereits überein; nichts wurde geändert.'
          : `${result.changes.length} ${
              result.changes.length === 1 ? 'Angabe wurde' : 'Angaben wurden'
            } übernommen.`,
        'success',
      )
    } catch (err: unknown) {
      const refused = gate.refusal(err, () => void apply(document, true), afterConfirmation)
      if (refused === null) {
        setRefusal(refusalOf(err, 'Die Einstellungen konnten nicht übernommen werden.'))
      } else if (refused) {
        setRefusal({ message: refused, details: [] })
      }
    } finally {
      setApplying(false)
    }
  }

  return (
    <PageSection
      title="Importieren"
      description="Übernimmt die Einstellungen aus einer exportierten Datei. Jeder Wert wird geprüft wie in der Verwaltung und gegen die Grenzen des Betriebs; besteht einer die Prüfung nicht, ändert sich nichts."
    >
      <Stack spacing={2} sx={{ alignItems: 'flex-start' }}>
        <Typography variant="body2" sx={{ color: 'text.secondary', maxWidth: readingWidth }}>
          Abschnitte, die die Datei nicht enthält, bleiben, wie sie sind. Passwörter und Schlüssel
          dieser Installation bleiben erhalten.
        </Typography>
        <Button
          variant="outlined"
          startIcon={<FileUploadOutlinedIcon />}
          disabled={checking || applying}
          onClick={() => fileInput.current?.click()}
        >
          {checking ? 'Datei wird geprüft …' : 'Datei auswählen'}
        </Button>
        <Box
          component="input"
          ref={fileInput}
          type="file"
          accept="application/json,.json"
          hidden
          aria-label="Einstellungsdatei auswählen"
          onChange={(event: ChangeEvent<HTMLInputElement>) => void handleFile(event)}
        />
        {refusal && (
          <Alert severity="error" role="alert" sx={{ maxWidth: readingWidth }}>
            {refusal.message}
            {refusal.details.length > 0 && (
              <Box component="ul" sx={{ m: 0, mt: 0.5, pl: 2.5 }}>
                {refusal.details.map((detail) => (
                  <li key={detail}>{detail}</li>
                ))}
              </Box>
            )}
          </Alert>
        )}
        {checked && checked.changes.length === 0 && (
          <Alert severity="info" sx={{ maxWidth: readingWidth }}>
            „{checked.fileName}“ stimmt mit dieser Installation überein; es gibt nichts zu
            übernehmen.
          </Alert>
        )}
        {checked && checked.changes.length > 0 && (
          <Stack spacing={2} sx={{ width: '100%' }}>
            <Typography variant="body2">
              „{checked.fileName}“ ändert {checked.changes.length}{' '}
              {checked.changes.length === 1 ? 'Angabe' : 'Angaben'}:
            </Typography>
            {securityRelevant(checked.changes) > 0 && (
              <Alert severity="warning" sx={{ maxWidth: readingWidth }}>
                {securityRelevant(checked.changes) === 1
                  ? 'Eine Änderung ist sicherheitsrelevant'
                  : `${securityRelevant(checked.changes)} Änderungen sind sicherheitsrelevant`}
                : Sie erweitern oder lenken um, was die Installation erreicht oder zulässt. Bitte
                vor dem Übernehmen prüfen.
              </Alert>
            )}
            <AdminList
              label="Änderungen durch den Import"
              rows={checked.changes}
              rowKey={(change) => `${change.section}.${change.key}`}
              rowLabel={(change) => `${change.sectionLabel}: ${change.keyLabel}`}
              columns={[
                {
                  id: 'section',
                  head: 'Bereich',
                  width: '18%',
                  card: 'line',
                  cell: (change) => change.sectionLabel,
                },
                {
                  id: 'key',
                  head: 'Angabe',
                  width: '26%',
                  card: 'title',
                  cell: (change) => (
                    <>
                      <ListText strong>{change.keyLabel}</ListText>
                      {change.securityRelevant && <MetaBadge>Sicherheitsrelevant</MetaBadge>}
                    </>
                  ),
                },
                {
                  id: 'before',
                  head: 'Bisher',
                  wrap: true,
                  cell: (change) => (
                    <Box component="span" sx={{ whiteSpace: 'pre-line' }}>
                      {formatSettingValue(change.before, change.section)}
                    </Box>
                  ),
                },
                {
                  id: 'after',
                  head: 'Neu',
                  wrap: true,
                  cell: (change) => (
                    <Box component="span" sx={{ whiteSpace: 'pre-line' }}>
                      {formatSettingValue(change.after, change.section)}
                    </Box>
                  ),
                },
              ]}
            />
            <Stack direction="row" spacing={1}>
              <BusyButton
                variant="contained"
                busy={applying}
                busyAnnouncement="Die Einstellungen werden übernommen"
                onClick={() => void handleApply()}
              >
                Übernehmen
              </BusyButton>
              <Button onClick={() => setChecked(null)} disabled={applying}>
                Verwerfen
              </Button>
            </Stack>
          </Stack>
        )}
      </Stack>
      {gate.dialog}
    </PageSection>
  )
}
