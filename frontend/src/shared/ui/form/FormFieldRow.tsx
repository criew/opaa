import Stack, { type StackProps } from '@mui/material/Stack'
import { LABELLED_FIELD_CLASS } from './FormField'
import { FORM_FIELD_LABEL_BLOCK_PX } from './FormFieldLabel'

/**
 * Fields that belong together in one line (server and port), or a field with the button that
 * submits it. Tops align; what carries no label of its own (a button, a switch) moves down by
 * the height of a label, so that it stands beside the field's box and not beside its label.
 * Below `sm` the row becomes a column.
 */
export default function FormFieldRow({ sx, ...rest }: StackProps) {
  return (
    <Stack
      direction={{ xs: 'column', sm: 'row' }}
      spacing={2}
      {...rest}
      sx={[
        (theme) => ({
          [theme.breakpoints.up('sm')]: {
            alignItems: 'flex-start',
            [`& > :not(.${LABELLED_FIELD_CLASS})`]: { mt: `${FORM_FIELD_LABEL_BLOCK_PX}px` },
          },
        }),
        ...(Array.isArray(sx) ? sx : [sx]),
      ]}
    />
  )
}
