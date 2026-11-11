import { useId } from 'react'
import Box from '@mui/material/Box'
import FormControlLabel from '@mui/material/FormControlLabel'
import Radio from '@mui/material/Radio'
import RadioGroup from '@mui/material/RadioGroup'
import Typography from '@mui/material/Typography'
import { fontSize } from '../../theme/tokens'
import FormFieldLabel from '../form/FormFieldLabel'

export interface SetupOption<V extends string> {
  value: V
  label: string
  /** What choosing the option means, in one sentence. */
  hint: string
}

interface OptionGroupProps<V extends string> {
  label: string
  options: ReadonlyArray<SetupOption<V>>
  value: V
  onChange: (value: V) => void
}

/** A choice of few options that each need a sentence: radio buttons under the field label. */
export default function OptionGroup<V extends string>({
  label,
  options,
  value,
  onChange,
}: OptionGroupProps<V>) {
  const labelId = useId()
  return (
    <Box>
      <FormFieldLabel id={labelId}>{label}</FormFieldLabel>
      <RadioGroup
        value={value}
        onChange={(event) => onChange(event.target.value as V)}
        aria-labelledby={labelId}
      >
        {options.map((option) => (
          <FormControlLabel
            key={option.value}
            value={option.value}
            control={<Radio size="small" />}
            sx={{ alignItems: 'flex-start', mb: 1, '& .MuiRadio-root': { mt: -0.5 } }}
            label={
              <Box>
                <Typography sx={{ fontSize: fontSize.text, fontWeight: 500 }}>
                  {option.label}
                </Typography>
                <Typography sx={{ fontSize: fontSize.text, color: 'text.secondary' }}>
                  {option.hint}
                </Typography>
              </Box>
            }
          />
        ))}
      </RadioGroup>
    </Box>
  )
}
