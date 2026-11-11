import Typography from '@mui/material/Typography'
import type { ReactNode } from 'react'
import { fontSize } from '../../theme/tokens'

/** Line height of the label and its gap to the control; `FormFieldRow` offsets by their sum. */
const LABEL_LINE_PX = 18
const LABEL_GAP_PX = 5
export const FORM_FIELD_LABEL_BLOCK_PX = LABEL_LINE_PX + LABEL_GAP_PX

interface FormFieldLabelProps {
  htmlFor?: string
  id?: string
  /** Appends „(optional)“; required fields carry no mark at all (guidelines 5.2). */
  optional?: boolean
  children: ReactNode
}

/**
 * The label of a form field: standalone above the control (12px, fg-2, 5px gap), never floating
 * inside it (guidelines 5.2). `FormField` renders it for text, select and autocomplete fields;
 * use it directly only for a control `FormField` cannot wrap (a radio group, a picker). Renders a
 * real <label> only when it targets a control via htmlFor; with just an id it stays a <span> for
 * aria-labelledby wiring.
 */
export default function FormFieldLabel({ htmlFor, id, optional, children }: FormFieldLabelProps) {
  return (
    <Typography
      component={htmlFor ? 'label' : 'span'}
      htmlFor={htmlFor}
      id={id}
      sx={{
        display: 'block',
        fontSize: fontSize.meta,
        lineHeight: `${LABEL_LINE_PX}px`,
        color: 'text.secondary',
        mb: `${LABEL_GAP_PX}px`,
      }}
    >
      {children}
      {optional ? ' (optional)' : ''}
    </Typography>
  )
}
