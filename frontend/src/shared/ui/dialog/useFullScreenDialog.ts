import { useTheme } from '@mui/material/styles'
import useMediaQuery from '@mui/material/useMediaQuery'

/** True below 600 px viewport width, where every dialog fills the screen (guidelines 5.6). */
export default function useFullScreenDialog(): boolean {
  const theme = useTheme()
  return useMediaQuery(theme.breakpoints.down('sm'))
}
