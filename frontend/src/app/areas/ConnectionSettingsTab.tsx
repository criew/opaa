import Typography from '@mui/material/Typography'
import { ConnectionLogRetentionSection } from '@/features/audit'
import { OutboundProxySection } from '@/features/connection'
import { PrivateStorageSection } from '@/features/library'

/**
 * The „Einstellungen“ tab of the connections area: installation-wide values that belong to no
 * single connection profile - the outbound proxy, the storage quota of private libraries and the
 * retention period of the connection log.
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
