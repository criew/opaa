import Typography from '@mui/material/Typography'
import type { ReactNode } from 'react'

interface FieldLabelProps {
  htmlFor?: string
  id?: string
  children: ReactNode
}

/**
 * The wizard form label shared by the space and library assistants: standalone above the field
 * (12px, fg-2, 5px gap) instead of MUI's floating variant (guidelines 5.2). Renders a real
 * <label> only when it targets a control via htmlFor; with just an id it stays a <span> for
 * aria-labelledby wiring.
 */
export default function FieldLabel({ htmlFor, id, children }: FieldLabelProps) {
  return (
    <Typography
      component={htmlFor ? 'label' : 'span'}
      htmlFor={htmlFor}
      id={id}
      sx={{ display: 'block', fontSize: 12, color: 'text.secondary', mb: '5px' }}
    >
      {children}
    </Typography>
  )
}
