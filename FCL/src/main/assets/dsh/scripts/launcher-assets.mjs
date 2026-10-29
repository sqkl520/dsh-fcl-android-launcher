/**
 * launcher-assets — give the framework's own asset responses a cache header.
 *
 * Why (BL-222): `@deepseek-ai/dsh-host-frontend-static` writes only
 * `content-type` (`lib/index.js:73`), so the external browser re-downloads and
 * re-gzips the whole frontend on every navigation. On a phone that is the
 * single largest avoidable cost in opening the UI.
 *
 * Why resolve the dist ourselves instead of tapping the existing handler: the
 * fallback seat is owned by frontend-static and exposes no hook; a prefix route
 * for `/assets` wins over the fallback (longest-prefix-wins, webserver:498-506)
 * without touching that package.
 *
 * Why fail-soft: if the dist cannot be resolved we register nothing at all.
 * The fallback keeps serving, the UI looks exactly as it did before, and the
 * only symptom is a warning line. A missing cache header must never cost a
 * working page.
 */
import { createReadStream, statSync } from 'node:fs'
import { createRequire } from 'node:module'
import { dirname, extname, join, normalize, sep } from 'node:path'

export const name = 'launcher-assets'

export const inject = ['webServer']

/** One year, immutable: the dist filenames carry a content hash. */
const IMMUTABLE = 'public, max-age=31536000, immutable'

const MIME = {
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
  '.otf': 'font/otf',
  '.map': 'application/json; charset=utf-8',
  '.wasm': 'application/wasm'
}

/**
 * Locate the frontend dist through the same install anchor the web bundle uses.
 * @param ctx - plugin context carrying profileContext.
 * @returns the absolute dist directory, or undefined when unresolvable.
 */
function resolveDist(ctx) {
  try {
    const anchor = ctx.get('profileContext')?.installAnchor
    if (typeof anchor !== 'string') return undefined
    const require = createRequire(anchor.replace(/package\.json$/, 'index.js'))
    const pkg = require.resolve('@deepseek-ai/dsh-web-frontend/package.json')
    return join(dirname(pkg), 'dist')
  } catch {
    return undefined
  }
}

/**
 * Map one request pathname to a file inside the dist, or undefined when the
 * path escapes the root. Decoding happens before the prefix check so that
 * `%2e%2e` cannot walk out after normalization.
 * @param distRoot - absolute dist directory.
 * @param pathname - the request pathname, already stripped of `/assets`.
 * @returns the absolute file path when it stays inside the root.
 */
function safeJoin(distRoot, pathname) {
  let decoded
  try {
    decoded = decodeURIComponent(pathname)
  } catch {
    return undefined
  }
  if (decoded.includes('\0')) return undefined
  const resolved = normalize(join(distRoot, decoded))
  const rootWithSep = distRoot.endsWith(sep) ? distRoot : distRoot + sep
  if (resolved !== distRoot && !resolved.startsWith(rootWithSep)) return undefined
  return resolved
}

export function apply(ctx) {
  const distRoot = resolveDist(ctx)
  if (distRoot === undefined) {
    ctx.logger.warn('launcher-assets: frontend dist not resolvable; serving without cache headers')
    return
  }

  const handler = (req, res) => {
    if (req.method !== 'GET' && req.method !== 'HEAD') {
      res.writeHead(405)
      res.end()
      return
    }
    const url = new URL(req.url ?? '/', 'http://x')
    const target = safeJoin(distRoot, url.pathname)
    if (target === undefined) {
      res.writeHead(404)
      res.end()
      return
    }
    let size
    try {
      const stat = statSync(target)
      if (!stat.isFile()) throw new Error('not a file')
      size = stat.size
    } catch {
      res.writeHead(404)
      res.end()
      return
    }
    // No content-length on purpose: the global gzip middleware skips a response
    // that already declares its length, and these assets are 3-5x smaller when
    // compressed. Let it decide.
    res.writeHead(200, {
      'content-type': MIME[extname(target).toLowerCase()] ?? 'application/octet-stream',
      'cache-control': IMMUTABLE,
      'x-launcher-asset': '1'
    })
    if (req.method === 'HEAD') {
      res.end()
      return
    }
    createReadStream(target).pipe(res)
  }

  ctx.effect(() => ctx.webServer.register({
    kind: 'prefix',
    path: '/assets',
    handler
  }), 'launcher-assets: /assets cache header')
}
