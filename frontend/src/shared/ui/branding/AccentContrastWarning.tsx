import { useMemo } from 'react'
import Alert from '@mui/material/Alert'
import AlertTitle from '@mui/material/AlertTitle'
import Box from '@mui/material/Box'
import { checkAccentContrast, formatContrastRatio, parseHexColor } from '@/shared/utils/contrast'
import { BRANDING_TEXTS } from './brandingSettings'

interface AccentContrastWarningProps {
  /** The accent colour as typed; nothing is shown while it is no `#RRGGBB` value. */
  primaryColor: string
}

/**
 * The contrast check of the accent colour (guidelines 7): it names every place the colour falls
 * short in either scheme and warns, it never blocks - a house's corporate colour is not this
 * application's to veto. Renders nothing while every check passes.
 */
export default function AccentContrastWarning({ primaryColor }: AccentContrastWarningProps) {
  const failingChecks = useMemo(
    () =>
      parseHexColor(primaryColor) === null
        ? []
        : checkAccentContrast(primaryColor).filter((check) => !check.passes),
    [primaryColor],
  )
  if (failingChecks.length === 0) return null
  return (
    <Alert severity="warning">
      <AlertTitle>{BRANDING_TEXTS.contrastTitle}</AlertTitle>
      {BRANDING_TEXTS.contrastWarning}
      <Box component="ul" sx={{ pl: 2.5, mb: 0, mt: 1 }}>
        {failingChecks.map((check) => (
          <li key={check.label}>
            {check.label}: {formatContrastRatio(check.ratio)} statt mindestens{' '}
            {formatContrastRatio(check.required)}
          </li>
        ))}
      </Box>
    </Alert>
  )
}
