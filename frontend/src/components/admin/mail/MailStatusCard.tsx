import Box from '@mui/material/Box'
import Paper from '@mui/material/Paper'
import Typography from '@mui/material/Typography'
import type { MailSettingsResponse } from '../../../types/api'
import { radius } from '../../../theme/tokens'
import { formatMailTimestamp, mailStatusOf } from './mailStatus'

const dotColor: Record<string, string> = {
  UNCONFIGURED: 'text.disabled',
  UNTESTED: 'warning.main',
  SUCCESS: 'success.main',
  FAILURE: 'error.main',
}

/**
 * „Letzter erfolgreicher Versand" und „letzter Fehler" an einer Stelle (ADR-0033, Entscheidung
 * 10) - beide Zeilen bleiben stehen, auch wenn die Verdikt-Zeile nur eine von beiden nennt: nach
 * einem geglückten Test ist der vorherige Fehler die Information, die erklärt, was repariert wurde.
 */
export default function MailStatusCard({ settings }: { settings: MailSettingsResponse }) {
  const status = mailStatusOf(settings)
  const success = formatMailTimestamp(settings.lastSuccessAt)
  const failure = formatMailTimestamp(settings.lastFailureAt)

  return (
    <Paper
      variant="outlined"
      component="section"
      aria-label="Versandstatus"
      sx={{ p: 2.5, borderRadius: `${radius.md}px`, mb: 2.5 }}
    >
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
        <Box
          aria-hidden
          sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: dotColor[status.kind] }}
        />
        <Typography component="h2" sx={{ fontSize: 14, fontWeight: 600 }}>
          {status.headline}
        </Typography>
      </Box>
      {status.detail && (
        <Typography sx={{ fontSize: 13, color: 'text.secondary', mt: 0.75 }}>
          {status.detail}
        </Typography>
      )}
      <Box
        component="dl"
        sx={{
          display: 'grid',
          gridTemplateColumns: { xs: '1fr', sm: 'max-content 1fr' },
          columnGap: 2,
          rowGap: 0.5,
          fontSize: 12.5,
          mt: 1.5,
          mb: 0,
        }}
      >
        <Box component="dt" sx={{ color: 'text.secondary' }}>
          Letzter erfolgreicher Versand
        </Box>
        <Box component="dd" sx={{ m: 0 }}>
          {success ?? 'noch keiner'}
        </Box>
        <Box component="dt" sx={{ color: 'text.secondary' }}>
          Letzter Fehler
        </Box>
        <Box component="dd" sx={{ m: 0 }}>
          {failure ? `${failure} — ${settings.lastFailureReason ?? 'ohne Grund'}` : 'keiner'}
        </Box>
      </Box>
    </Paper>
  )
}
