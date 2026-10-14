import Typography from '@mui/material/Typography'
import { ConnectionLogRetentionSection } from '@/features/audit'
import { OutboundProxySection } from '@/features/connection'
import { PrivateStorageSection } from '@/features/library'

/**
 * Der Reiter „Einstellungen“ der Anbindungen: hausweite Werte, die keinem einzelnen Zugang gehören -
 * der Ausgangsproxy, das Speicherkontingent privater Bibliotheken und die Aufbewahrungsfrist des
 * Verbindungsprotokolls.
 */
export default function ConnectionSettingsTab() {
  return (
    <>
      <Typography sx={{ fontSize: 13.5, color: 'text.secondary', mb: 2.5, maxWidth: 760 }}>
        Diese Werte gelten für die ganze Installation, nicht für einen einzelnen Zugang: wie OPAA
        nach außen verbindet, wie viel Speicher private Bibliotheken belegen dürfen und wie lange
        das Verbindungsprotokoll aufbewahrt wird.
      </Typography>
      <OutboundProxySection />
      <PrivateStorageSection />
      <ConnectionLogRetentionSection />
    </>
  )
}
