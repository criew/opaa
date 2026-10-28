import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import FileDownloadOutlinedIcon from '@mui/icons-material/FileDownloadOutlined'
import { notify } from '@/shared/stores/notificationStore'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import PageSection from '@/shared/ui/PageSection'
import { exportSettings } from '../api/settingsApi'
import { downloadSettings, settingsFileName } from '../utils/settingsFile'

/** Saves every setting of the installation as one JSON file, without secrets. */
export default function SettingsExportSection() {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function handleExport() {
    setBusy(true)
    setError(null)
    try {
      const document = await exportSettings()
      downloadSettings(document, settingsFileName(new Date()))
      notify('Die Einstellungen wurden exportiert.', 'success')
    } catch (err: unknown) {
      setError(
        err instanceof Error ? err.message : 'Die Einstellungen konnten nicht exportiert werden.',
      )
    } finally {
      setBusy(false)
    }
  }

  return (
    <PageSection
      title="Exportieren"
      description="Speichert alle Einstellungen der Verwaltung als eine Datei, etwa um Test und Produktion zu vergleichen oder eine neue Installation vorzubereiten."
    >
      <Stack spacing={2} sx={{ maxWidth: 640, alignItems: 'flex-start' }}>
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          Ohne Passwörter und Schlüssel. Modelle, Identitätsanbieter, Verzeichniszugänge, Zugänge,
          Konnektor-Freigabe und Bilder des Brandings gehören nicht dazu.
        </Typography>
        <BusyButton
          variant="outlined"
          startIcon={<FileDownloadOutlinedIcon />}
          busy={busy}
          busyAnnouncement="Die Einstellungen werden exportiert"
          onClick={() => void handleExport()}
        >
          Einstellungen exportieren
        </BusyButton>
        {error && (
          <Alert severity="error" role="alert">
            {error}
          </Alert>
        )}
      </Stack>
    </PageSection>
  )
}
