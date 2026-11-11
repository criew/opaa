import { describe, expect, it } from 'vitest'
import * as routes from '@/shared/routes'

/**
 * Every page that takes a secret from its query - the token of a mail link, the code and state of a
 * sign-in or consent callback - is a path the access log of frontend/nginx.conf masks, in the
 * request line ($opaa_logged_uri) and in the Referer of the page's own requests
 * ($opaa_logged_referer). A page reads it itself, through a hook or function that does, or is the
 * redirect_uri the sign-in provider returns to; a reader passes it on to every file that calls or
 * renders (`<Name`) one of its exports.
 *
 * Not seen: other parameter names, a secret in a path segment (useParams) or in the hash, and a
 * page mounted other than by `<Route path=... element={<Page />} />` in App.tsx.
 */
const raw = (files: Record<string, unknown>) => files as Record<string, string>
const SOURCES = Object.fromEntries(
  Object.entries(
    raw(
      import.meta.glob(['../**/*.{ts,tsx}', '!../**/*.test.{ts,tsx}', '!../**/mocks/**'], {
        query: '?raw',
        import: 'default',
        eager: true,
      }),
    ),
  ).map(([path, source]) => [path, stripComments(source)]),
)
const NGINX_CONF = Object.values(
  raw(import.meta.glob('../../nginx.conf', { query: '?raw', import: 'default', eager: true })),
)[0]

const SECRET_PARAMETER = /\.get\(\s*['"](?:token|code|state)['"]\s*\)/
const REDIRECT_URI = /\bredirect_uri\s*:\s*`\$\{window\.location\.origin\}(\/[^`?]*)`/g
const MOUNTED_ROUTE = /<Route\s+path=(?:\{(\w+)\}|"([^"]+)")\s+element=\{<(\w+)\s*\/>\}/g
const EXPORTED_FUNCTION = /export\s+(?:default\s+)?(?:function|const)\s+(\w+)/g

function stripComments(source: string) {
  return source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
}

function fileName(path: string) {
  return path.replace(/^.*\//, '').replace(/\.tsx?$/, '')
}

/** The files that read a secret parameter themselves, or call or render an export of one. */
function secretReaders(): Set<string> {
  const readers = new Set(
    Object.keys(SOURCES).filter((path) => SECRET_PARAMETER.test(SOURCES[path])),
  )
  for (let grown = true; grown;) {
    grown = false
    const names = [...readers].flatMap((path) =>
      [...SOURCES[path].matchAll(EXPORTED_FUNCTION)].map((match) => match[1]),
    )
    for (const [path, source] of Object.entries(SOURCES)) {
      if (
        !readers.has(path) &&
        names.some((name) => new RegExp(`(?:\\b${name}\\(|<${name}\\b)`).test(source))
      ) {
        readers.add(path)
        grown = true
      }
    }
  }
  return readers
}

function mountedRoutes(): Map<string, string> {
  const app = SOURCES['./App.tsx']
  const constants = routes as unknown as Record<string, unknown>
  const mounted = new Map<string, string>()
  for (const [, constant, literal, page] of app.matchAll(MOUNTED_ROUTE)) {
    mounted.set(page, literal ?? String(constants[constant]))
  }
  return mounted
}

/** The pages whose query carries a secret, with where that was found. */
function secretRoutes(): Map<string, string> {
  const mounted = mountedRoutes()
  const found = new Map<string, string>()
  for (const path of secretReaders()) {
    const route = mounted.get(fileName(path))
    if (route) {
      found.set(route, path)
    } else if (path.includes('/pages/')) {
      found.set(`<${fileName(path)} is mounted under no route App.tsx names>`, path)
    }
  }
  for (const [path, source] of Object.entries(SOURCES)) {
    for (const match of source.matchAll(REDIRECT_URI)) {
      found.set(match[1], path)
    }
  }
  return found
}

function mapPattern(variable: string): RegExp {
  const map = new RegExp(`map \\$\\w+ \\$${variable} \\{[^}]*?"~([^"]+)"\\s+\\$1;`).exec(NGINX_CONF)
  if (!map) {
    throw new Error(`frontend/nginx.conf has no masking map for $${variable}`)
  }
  return new RegExp(map[1])
}

function logged(pattern: RegExp, value: string): string {
  return pattern.exec(value)?.[1] ?? value
}

describe('pages with a secret in the query', () => {
  const found = secretRoutes()

  it('finds the pages it checks', () => {
    expect([...found.keys()].sort()).toEqual(
      expect.arrayContaining([
        routes.SET_PASSWORD_ROUTE,
        routes.VERIFY_EMAIL_ROUTE,
        routes.HANDOVER_ROUTE,
        routes.AUTH_CALLBACK_ROUTE,
        routes.CONNECTION_CALLBACK_ROUTE,
      ]),
    )
  })

  it.each([...found.entries()])('%s (read in %s) is logged without its query', (route) => {
    const uri = mapPattern('opaa_logged_uri')
    const referer = mapPattern('opaa_logged_referer')
    for (const query of ['?token=SENTINEL', '?code=SENTINEL&state=SENTINEL']) {
      expect(logged(uri, `${route}${query}`)).toBe(route)
      expect(logged(uri, `${route}/${query}`)).toBe(route)
      expect(logged(referer, `https://opaa.example${route}${query}`)).toBe(
        `https://opaa.example${route}`,
      )
      expect(logged(referer, `https://opaa.example${route}/${query}`)).toBe(
        `https://opaa.example${route}`,
      )
    }
  })

  it('keeps the query of every other page', () => {
    expect(logged(mapPattern('opaa_logged_uri'), '/search?q=Haushalt')).toBe('/search?q=Haushalt')
  })
})
