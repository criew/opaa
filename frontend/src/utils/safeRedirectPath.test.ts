import { describe, expect, it } from 'vitest'
import { redirectTargetOf, safeRedirectPath } from './safeRedirectPath'

describe('safeRedirectPath', () => {
  it('keeps a same-origin path with query and fragment', () => {
    expect(safeRedirectPath('/spaces/1?tab=docs#top')).toBe('/spaces/1?tab=docs#top')
  })

  it('falls back for an absolute URL to another origin', () => {
    expect(safeRedirectPath('https://evil.example/pwn')).toBe('/chat')
  })

  // Every shape the URL parser folds into a protocol-relative target: a backslash counts as a
  // separator, and a control character is dropped before parsing.
  it.each([
    '//evil.example/pwn',
    '/\\evil.example',
    '/\\\\evil',
    '/\\/evil.example',
    '/\r//evil',
    '/\t//evil',
  ])('falls back for the protocol-relative target %j', (target) => {
    expect(safeRedirectPath(target)).toBe('/chat')
  })

  it('falls back for a percent-encoded protocol-relative target', () => {
    expect(safeRedirectPath('/%2F%2Fevil.example')).toBe('/chat')
  })

  it('falls back for an empty or relative target', () => {
    expect(safeRedirectPath(null)).toBe('/chat')
    expect(safeRedirectPath('chat')).toBe('/chat')
  })

  it('uses the given fallback', () => {
    expect(safeRedirectPath('https://evil.example', '/login')).toBe('/login')
  })
})

describe('redirectTargetOf', () => {
  it('prefers the router state ProtectedRoute handed on', () => {
    expect(redirectTargetOf({ from: '/spaces/7' }, '?from=/settings')).toBe('/spaces/7')
  })

  it('falls back to the query parameter of a link from outside', () => {
    expect(redirectTargetOf(null, '?from=/settings')).toBe('/settings')
  })

  it('refuses an off-origin target in either place', () => {
    expect(redirectTargetOf({ from: 'https://evil.example' }, '')).toBe('/chat')
    expect(redirectTargetOf(null, '?from=//evil.example')).toBe('/chat')
  })
})
