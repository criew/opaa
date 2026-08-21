import Box from '@mui/material/Box'

interface WizardStepBarProps {
  steps: readonly string[]
  active: number
}

/**
 * The wizard step bar shared by the space and library assistants (mockups 1b/1e): a numbered
 * list on a hairline, the active step carrying text.primary at weight 500 with a 2px accent
 * underline overlapping the divider.
 */
export default function WizardStepBar({ steps, active }: WizardStepBarProps) {
  return (
    <Box
      component="ol"
      sx={{
        display: 'flex',
        listStyle: 'none',
        m: 0,
        mb: 3.5,
        p: 0,
        fontSize: 12.5,
        borderBottom: 1,
        borderColor: 'divider',
      }}
    >
      {steps.map((step, index) => (
        <Box
          component="li"
          key={step}
          aria-current={index === active ? 'step' : undefined}
          sx={{
            pr: 2.25,
            pl: index === 0 ? 0 : 2.25,
            pb: 1.25,
            color: index === active ? 'text.primary' : 'text.secondary',
            fontWeight: index === active ? 500 : 400,
            borderBottom: index === active ? 2 : 0,
            borderColor: 'primary.main',
            mb: index === active ? '-1px' : 0,
          }}
        >
          {index + 1} · {step}
        </Box>
      ))}
    </Box>
  )
}
