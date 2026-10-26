import Typography from '@mui/material/Typography'
import { ConnectionLogRetentionSection } from '@/features/audit'
import { ConnectionLifecycleSection, OutboundProxySection } from '@/features/connection'
import { FullSyncRhythmSection, PrivateStorageSection } from '@/features/library'
import { LinkTargetsSection } from '@/features/link-targets'

/**
 * The „Einstellungen“ tab of the connections area: installation-wide values that belong to no
 * single connection profile - the outbound proxy, the allowed link targets of answers, the storage
 * quota of private libraries, the full-sync rhythm per source type, the thresholds and periods of
 * the connections and the retention period of the connection log.
 */
export default function ConnectionSettingsTab() {
  return (
    <>
      <Typography sx={{ fontSize: 13.5, color: 'text.secondary', mb: 2.5, maxWidth: 760 }}>
        Diese Werte gelten für die ganze Installation, nicht für einen einzelnen Zugang: wie OPAA
        nach außen verbindet, wohin Links in Antworten führen dürfen, wie viel Speicher private
        Bibliotheken belegen dürfen, wie oft Bibliotheken ihre Quelle vollständig abgleichen, wann
        Verbindungen ruhen und private Bibliotheken gelöscht werden und wie lange das
        Verbindungsprotokoll aufbewahrt wird.
      </Typography>
      <OutboundProxySection />
      <LinkTargetsSection />
      <PrivateStorageSection />
      <FullSyncRhythmSection />
      <ConnectionLifecycleSection />
      <ConnectionLogRetentionSection />
    </>
  )
}
