/**
 * Whether two base addresses name the same endpoint: scheme, host, port (the default one when
 * omitted) and path without trailing slashes, as the server decides whether a stored key may stay.
 */
export function sameEndpoint(previous: string, next: string): boolean {
  const left = endpointKey(previous)
  const right = endpointKey(next)
  return left !== null && left === right
}

function endpointKey(address: string): string | null {
  let url: URL
  try {
    url = new URL(address.trim())
  } catch {
    return null
  }
  const scheme = url.protocol.replace(':', '').toLowerCase()
  const port = url.port !== '' ? url.port : scheme === 'https' ? '443' : '80'
  const path = url.pathname.replace(/\/+$/, '')
  return `${scheme}://${url.hostname.toLowerCase()}:${port}${path}`
}

const NUMBER = new Intl.NumberFormat('de-DE')

export function formatCount(value: number): string {
  return NUMBER.format(value)
}

/** A rough duration, as the preview names it. */
export function formatDuration(seconds: number): string {
  if (seconds < 60) return 'unter einer Minute'
  const minutes = Math.round(seconds / 60)
  if (minutes < 90) return `etwa ${minutes} ${minutes === 1 ? 'Minute' : 'Minuten'}`
  const hours = Math.round(seconds / 3600)
  if (hours < 48) return `etwa ${hours} Stunden`
  return `etwa ${Math.round(hours / 24)} Tage`
}

/** Bytes in the unit a person reads, with one decimal from a megabyte on. */
export function formatBytes(bytes: number): string {
  const units = ['Byte', 'KB', 'MB', 'GB', 'TB']
  let value = bytes
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit++
  }
  const digits = unit >= 2 ? 1 : 0
  return `${new Intl.NumberFormat('de-DE', { maximumFractionDigits: digits }).format(value)} ${units[unit]}`
}

/** A token count rounded to millions or thousands, as an estimate. */
export function formatTokens(tokens: number): string {
  if (tokens >= 1_000_000) {
    return `etwa ${new Intl.NumberFormat('de-DE', { maximumFractionDigits: 1 }).format(tokens / 1_000_000)} Mio.`
  }
  if (tokens >= 1000) return `etwa ${formatCount(Math.round(tokens / 1000))} Tsd.`
  return `etwa ${formatCount(tokens)}`
}
