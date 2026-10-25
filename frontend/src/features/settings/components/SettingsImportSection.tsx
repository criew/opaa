import { useState, type ChangeEvent } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableContainer from '@mui/material/TableContainer'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import Typography from '@mui/material/Typography'
import FileUploadOutlinedIcon from '@mui/icons-material/FileUploadOutlined'
import type { SettingsChange, SettingsDocument } from '@/shared/api/types'
import { apiFieldErrors } from '@/shared/api/apiErrorDetails'
import { confirmAction } from '@/shared/stores/confirmStore'
import { notify } from '@/shared/stores/notificationStore'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import PageSection from '@/shared/ui/PageSection'
import { importSettings, previewSettingsImport } from '../api/settingsApi'
import {
  MAX_SETTINGS_FILE_BYTES,
  formatSettingValue,
  parseSettingsFile,
} from '../utils/settingsFile'

interface Checked {
  fileName: string
  document: SettingsDocument
  changes: SettingsChange[]
}

interface Refusal {
  message: string
  details: string[]
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
  const [checked, setChecked] = useState<Checked | null>(null)
  const [refusal, setRefusal] = useState<Refusal | null>(null)
  const [checking, setChecking] = useState(false)
  const [applying, setApplying] = useState(false)

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
      } sofort für die ganze Installation. Jede Änderung steht im Revisionsprotokoll.`,
      confirmLabel: 'Übernehmen',
      tone: 'caution',
    })
    if (!confirmed) return
    setApplying(true)
    try {
      const result = await importSettings(checked.document)
      setChecked(null)
      notify(
        result.changes.length === 0
          ? 'Die Einstellungen stimmten bereits überein; nichts wurde geändert.'
          : `${result.changes.length} ${
              result.changes.length === 1 ? 'Angabe wurde' : 'Angaben wurden'
            } übernommen.`,
        'success',
      )
    } catch (err: unknown) {
      setRefusal(refusalOf(err, 'Die Einstellungen konnten nicht übernommen werden.'))
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
        <Typography variant="body2" sx={{ color: 'text.secondary', maxWidth: 640 }}>
          Abschnitte, die die Datei nicht enthält, bleiben, wie sie sind. Passwörter und Schlüssel
          dieser Installation bleiben erhalten.
        </Typography>
        <Button
          component="label"
          variant="outlined"
          startIcon={<FileUploadOutlinedIcon />}
          disabled={checking || applying}
        >
          {checking ? 'Datei wird geprüft …' : 'Datei auswählen'}
          <Box
            component="input"
            type="file"
            accept="application/json,.json"
            hidden
            aria-label="Einstellungsdatei auswählen"
            onChange={(event: ChangeEvent<HTMLInputElement>) => void handleFile(event)}
          />
        </Button>
        {refusal && (
          <Alert severity="error" role="alert" sx={{ maxWidth: 640 }}>
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
          <Alert severity="info" sx={{ maxWidth: 640 }}>
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
            <TableContainer>
              <Table size="small" aria-label="Änderungen durch den Import">
                <TableHead>
                  <TableRow>
                    <TableCell>Bereich</TableCell>
                    <TableCell>Angabe</TableCell>
                    <TableCell>Bisher</TableCell>
                    <TableCell>Neu</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {checked.changes.map((change) => (
                    <TableRow key={`${change.section}.${change.key}`}>
                      <TableCell>{change.sectionLabel}</TableCell>
                      <TableCell sx={{ fontFamily: 'monospace' }}>{change.key}</TableCell>
                      <TableCell sx={{ overflowWrap: 'anywhere' }}>
                        {formatSettingValue(change.before)}
                      </TableCell>
                      <TableCell sx={{ overflowWrap: 'anywhere' }}>
                        {formatSettingValue(change.after)}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
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
    </PageSection>
  )
}
