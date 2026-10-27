import Alert from '@mui/material/Alert'
import AlertTitle from '@mui/material/AlertTitle'
import type { SxProps, Theme } from '@mui/material/styles'

/**
 * The notice that mails carry no usable link while `OPAA_PUBLIC_BASE_URL` is not set; the mail
 * administration and the mail step of the installation show the same sentence.
 */
export default function PublicBaseUrlMissingAlert({ sx }: { sx?: SxProps<Theme> }) {
  return (
    <Alert severity="warning" sx={sx}>
      <AlertTitle>Öffentliche Adresse fehlt</AlertTitle>
      OPAA_PUBLIC_BASE_URL ist nicht gesetzt. Einladungen, Rücksetzlinks und Bestätigungen enthalten
      dann keinen brauchbaren Link — die betroffenen Wege bleiben abgeschaltet. Tragen Sie die
      Adresse, unter der diese Installation erreichbar ist, in der Umgebung der Bereitstellung ein;
      im Browser ist sie bewusst nicht änderbar.
    </Alert>
  )
}
