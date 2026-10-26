import { describe, expect, it } from 'vitest'
import { formatBytes, formatDuration, formatTokens, sameEndpoint } from './embeddingChange'

describe('sameEndpoint', () => {
  it('ignores a default port and trailing slashes', () => {
    expect(sameEndpoint('http://ollama/v1/', 'http://ollama:80/v1')).toBe(true)
    expect(sameEndpoint('https://api.example.org/v1', 'https://API.example.org:443/v1')).toBe(true)
  })

  it('tells another host, port, path or scheme apart', () => {
    expect(sameEndpoint('http://ollama:11434/v1', 'http://ersatz:11434/v1')).toBe(false)
    expect(sameEndpoint('http://ollama:11434/v1', 'http://ollama:11435/v1')).toBe(false)
    expect(sameEndpoint('http://ollama:11434/v1', 'http://ollama:11434/v2')).toBe(false)
    expect(sameEndpoint('http://ollama/v1', 'https://ollama/v1')).toBe(false)
  })

  it('never matches an address that is no URL', () => {
    expect(sameEndpoint('kein-url', 'kein-url')).toBe(false)
  })
})

describe('the preview numbers', () => {
  it('names durations roughly', () => {
    expect(formatDuration(30)).toBe('unter einer Minute')
    expect(formatDuration(40 * 60)).toBe('etwa 40 Minuten')
    expect(formatDuration(7 * 3600)).toBe('etwa 7 Stunden')
    expect(formatDuration(3 * 86400)).toBe('etwa 3 Tage')
  })

  it('names sizes and tokens in readable units', () => {
    expect(formatBytes(1536 * 1024 * 1024)).toBe('1,5 GB')
    expect(formatBytes(900)).toBe('900 Byte')
    expect(formatTokens(19_200_000)).toBe('etwa 19,2 Mio.')
    expect(formatTokens(48_000)).toBe('etwa 48 Tsd.')
  })
})
