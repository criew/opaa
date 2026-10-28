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
      OPAA_PUBLIC_BASE_URL ist nicht gesetzt, deshalb bleiben Einladungen, Rücksetzlinks und
      Bestätigungen abgeschaltet. Die Adresse wird in der Umgebung der Bereitstellung eingetragen.
    </Alert>
  )
}
