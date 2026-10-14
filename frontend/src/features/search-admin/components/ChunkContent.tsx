import Box from '@mui/material/Box'

/** Monospace, line breaks preserved: the chunk exactly as the index holds it, prefix included. */
export default function ChunkContent({ content }: { content: string }) {
  return (
    <Box
      component="pre"
      sx={{
        m: 0,
        p: 1.5,
        fontFamily: 'monospace',
        fontSize: '0.8125rem',
        whiteSpace: 'pre-wrap',
        wordBreak: 'break-word',
        bgcolor: 'action.hover',
        borderRadius: 1,
        maxHeight: 420,
        overflow: 'auto',
      }}
    >
      {content}
    </Box>
  )
}
