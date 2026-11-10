import { createPortal } from 'react-dom'
import Typography from '@mui/material/Typography'
import { fontSize } from '../theme/tokens'
import { formatAreaCount, type AreaCountValue } from './areaCount'
import { useAreaHeaderSlot } from './areaHeaderSlots'

/**
 * The count of the list a page or its selected tab shows, placed next to the title of the page's
 * `AreaPageHeader` from wherever the list lives (guidelines 4.6). It follows the tab because it
 * is rendered by the tab's content. Rendered on its own, without a header, it stays in place.
 */
export default function AreaPageCount(props: AreaCountValue) {
  const slot = useAreaHeaderSlot('count')
  const count = (
    <Typography component="span" sx={{ fontSize: fontSize.text, color: 'text.secondary' }}>
      {formatAreaCount(props)}
    </Typography>
  )
  return slot ? createPortal(count, slot) : count
}
