import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import BrandingPreview from './BrandingPreview'

interface BrandingPreviewsProps {
  productName: string
  claim: string
  primaryColor: string
  logoUrl?: string
}

/**
 * The light and the dark preview side by side, stacked below 600 px: both schemes are equally
 * binding, so a form never shows only one of them.
 */
export default function BrandingPreviews(props: BrandingPreviewsProps) {
  return (
    <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
      <Box sx={{ flex: 1 }}>
        <BrandingPreview mode="light" {...props} />
      </Box>
      <Box sx={{ flex: 1 }}>
        <BrandingPreview mode="dark" {...props} />
      </Box>
    </Stack>
  )
}
