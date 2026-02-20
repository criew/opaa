import Chip from '@mui/material/Chip'
import Box from '@mui/material/Box'

interface DateSeparatorProps {
  date: Date
}

export default function DateSeparator({ date }: DateSeparatorProps) {
  const label = date.toLocaleDateString(undefined, {
    weekday: 'long',
    year: 'numeric',
    month: 'long',
    day: 'numeric',
  })

  return (
    <Box sx={{ display: 'flex', justifyContent: 'center', my: 2 }}>
      <Chip label={label} size="small" variant="outlined" />
    </Box>
  )
}
