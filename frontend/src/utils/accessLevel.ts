import type { AccessLevel } from '../types/chat'

// TODO: Replace with server-provided access level once API supports it
export function deriveAccessLevel(fileName: string): AccessLevel {
  const lower = fileName.toLowerCase()
  if (lower.endsWith('.pdf')) return 'Confidential'
  if (lower.endsWith('.md')) return 'Public'
  return 'Internal'
}
