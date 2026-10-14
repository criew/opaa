import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { findMisassigned } from './assignment.js'
import { ALLOWED, THEMES } from './themes.js'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

describe('themes.js', () => {
  it('assigns every file under src/ to exactly one theme or bucket', () => {
    const misassigned = findMisassigned(root)

    expect(misassigned).toEqual([])
  })

  it('lists the allowed dependencies of every theme and only known themes as targets', () => {
    expect(Object.keys(ALLOWED).sort()).toEqual([...THEMES].sort())
    for (const [name, targets] of Object.entries(ALLOWED)) {
      for (const target of targets) {
        expect(THEMES, `${name} -> ${target}`).toContain(target)
        expect(target).not.toBe(name)
      }
    }
  })

  it('keeps the dependency graph between themes free of cycles', () => {
    const state = {}
    const cycles = []
    const visit = (name, trail) => {
      if (state[name] === 'done') return
      if (state[name] === 'active') {
        cycles.push([...trail.slice(trail.indexOf(name)), name].join(' -> '))
        return
      }
      state[name] = 'active'
      for (const target of ALLOWED[name]) visit(target, [...trail, name])
      state[name] = 'done'
    }
    for (const name of Object.keys(ALLOWED)) visit(name, [])

    expect(cycles).toEqual([])
  })
})
