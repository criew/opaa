import Chip from '@mui/material/Chip'
import Box from '@mui/material/Box'
import { formatDayHeading } from '@/shared/utils/formatDate'

interface DateSeparatorProps {
  date: Date
}

export default function DateSeparator({ date }: DateSeparatorProps) {
  const label = formatDayHeading(date)

  return (
    <Box sx={{ display: 'flex', justifyContent: 'center', my: 2 }}>
      <Chip label={label} size="small" variant="outlined" />
    </Box>
  )
}
