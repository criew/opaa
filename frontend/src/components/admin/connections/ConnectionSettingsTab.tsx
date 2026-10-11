import Typography from '@mui/material/Typography'
import ConnectionLogRetentionSection from './ConnectionLogRetentionSection'
import OutboundProxySection from './OutboundProxySection'
import PrivateStorageSection from './PrivateStorageSection'

/**
 * Der Reiter „Einstellungen“ der Anbindungen: hausweite Werte, die keinem einzelnen Zugang gehören -
 * der Ausgangsproxy, das Speicherkontingent privater Bibliotheken und die Aufbewahrungsfrist des
 * Verbindungsprotokolls.
 */
export default function ConnectionSettingsTab() {
  return (
    <>
      <Typography sx={{ fontSize: 13.5, color: 'text.secondary', mb: 2.5, maxWidth: 760 }}>
        Diese Werte gelten für die ganze Installation, nicht für einen einzelnen Zugang.
      </Typography>
      <OutboundProxySection />
      <PrivateStorageSection />
      <ConnectionLogRetentionSection />
    </>
  )
}
