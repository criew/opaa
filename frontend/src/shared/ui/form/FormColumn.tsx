import Stack, { type StackProps } from '@mui/material/Stack'
import { formWidth } from '../../theme/tokens'

/**
 * The form of a settings page (guidelines 5.2): one column, 640 px wide, fields stacked. Only
 * value pairs that are read as one (server and port) stand side by side, in a row inside it.
 */
export default function FormColumn({ sx, ...rest }: StackProps) {
  return (
    <Stack
      spacing={2}
      {...rest}
      sx={[{ maxWidth: formWidth.column }, ...(Array.isArray(sx) ? sx : [sx])]}
    />
  )
}
