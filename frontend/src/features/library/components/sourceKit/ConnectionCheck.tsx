import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'
import visuallyHidden from '@mui/utils/visuallyHidden'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import type { SourceConnectionTestResponse } from '@/shared/api/types'
import type { ConnectionCheckState } from './useConnectionCheck'

const REFUSED_SHOWN = 5

/** The button and the inline result of {@link useConnectionCheck}. */
export default function ConnectionCheck({
  check,
  id,
  onTested,
}: {
  check: ConnectionCheckState
  id: string
  onTested?: (result: SourceConnectionTestResponse | undefined) => void
}) {
  const outcome = check.outcome
  const failed = outcome?.stages.some((stage) => stage.state === 'failed') ?? false
  return (
    <Box id={id} tabIndex={-1} sx={{ outline: 'none' }}>
      <BusyButton
        variant="outlined"
        busy={check.running}
        busyAnnouncement="Verbindung wird getestet"
        onClick={() => void check.run().then((r) => onTested?.(r.result))}
      >
        {check.running ? 'Verbindung wird getestet …' : 'Verbindung testen'}
      </BusyButton>
      <Box role="status" aria-live="polite">
        {outcome && (
          <Box
            sx={{
              mt: 1.5,
              pl: 1.5,
              borderLeft: 3,
              borderColor: failed ? 'error.main' : 'success.main',
            }}
          >
            <Box
              component="ul"
              sx={{ listStyle: 'none', m: 0, p: 0, display: 'flex', flexWrap: 'wrap', gap: 2 }}
              aria-label="Prüfstufen"
            >
              {outcome.stages.map((stage) => (
                <Box
                  component="li"
                  key={stage.name}
                  sx={{ fontSize: 14, display: 'flex', gap: 0.5 }}
                >
                  <Box
                    component="span"
                    aria-hidden
                    sx={{
                      color: stage.state === 'ok' ? 'success.main' : 'error.main',
                      fontWeight: 600,
                    }}
                  >
                    {stage.state === 'ok' ? '✓' : '✗'}
                  </Box>
                  <span>
                    {stage.name}
                    <Box component="span" sx={visuallyHidden}>
                      {stage.state === 'ok' ? ' in Ordnung' : ' fehlgeschlagen'}
                    </Box>
                  </span>
                </Box>
              ))}
            </Box>
            <Typography sx={{ fontSize: 13.5, color: 'text.secondary', mt: 0.5 }}>
              {outcome.message}
            </Typography>
            {outcome.refused.length > 0 && (
              <Box
                component="ul"
                sx={{ m: 0, mt: 0.5, pl: 2.5, fontSize: 13, color: 'text.secondary' }}
              >
                {outcome.refused.slice(0, REFUSED_SHOWN).map((line) => (
                  <li key={line} style={{ overflowWrap: 'anywhere' }}>
                    {line}
                  </li>
                ))}
                {outcome.refused.length > REFUSED_SHOWN && (
                  <li>und {outcome.refused.length - REFUSED_SHOWN} weitere</li>
                )}
              </Box>
            )}
            {failed && !check.waived && (
              <Button size="small" sx={{ mt: 0.5, px: 0.5 }} onClick={check.waive}>
                Trotzdem fortfahren
              </Button>
            )}
            {failed && check.waived && (
              <Typography sx={{ fontSize: 13, color: 'text.secondary', mt: 0.5 }}>
                Wird trotz fehlgeschlagenem Test übernommen.
              </Typography>
            )}
          </Box>
        )}
      </Box>
    </Box>
  )
}
