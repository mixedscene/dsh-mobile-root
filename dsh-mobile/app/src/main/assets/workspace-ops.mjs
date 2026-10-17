import fs from 'node:fs'
import crypto from 'node:crypto'
import { TextDecoder } from 'node:util'

const C = fs.constants
const DIRECTORY = C.O_RDONLY | C.O_DIRECTORY | C.O_NOFOLLOW
const TEXT_LIMIT = 1024 * 1024
const FILE_LIMIT = 32 * TEXT_LIMIT
const RESERVED = '.dsh-ops-'
const fail = message => { throw new Error(message) }
const name = value => {
  if (typeof value !== 'string' || !value || value === '.' || value === '..' || value.includes('/') || value.includes('\0') || value.startsWith(RESERVED)) fail('Invalid or reserved name')
  return value
}
const parts = value => {
  if (typeof value !== 'string' || value.startsWith('/') || value.includes('\0')) fail('Expected a relative workspace path')
  return value === '' ? [] : value.split('/').map(name)
}
const fdPath = fd => `/proc/self/fd/${fd}`
const entryPath = (fd, value) => `${fdPath(fd)}/${name(value)}`
const fingerprint = stat => ({ dev: `${stat.dev}`, ino: `${stat.ino}`, size: `${stat.size}`, mtimeNs: `${stat.mtimeNs}`, ctimeNs: `${stat.ctimeNs}`, mode: `${stat.mode}` })
const same = (a, b) => a && b && Object.keys(a).length === Object.keys(b).length && Object.keys(a).every(key => a[key] === b[key])
const lstat = path => fs.lstatSync(path, { bigint: true })
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex')

function boundedRead(fd, limit) {
  const chunks = []; let total = 0
  while (true) {
    const chunk = Buffer.alloc(Math.min(65536, limit + 1 - total))
    const n = fs.readSync(fd, chunk, 0, chunk.length, null)
    if (!n) break
    chunks.push(chunk.subarray(0, n)); total += n
    if (total > limit) fail('File exceeds size limit')
  }
  return Buffer.concat(chunks, total)
}
function snapshot(parent, child, limit = TEXT_LIMIT) {
  const fd = fs.openSync(entryPath(parent, child), C.O_RDONLY | C.O_NOFOLLOW | C.O_NONBLOCK)
  try {
    const before = fs.fstatSync(fd, { bigint: true })
    if (!before.isFile()) fail('Only regular files can be read or edited')
    if (before.size > BigInt(limit)) fail('File exceeds preview limit; use export')
    const bytes = boundedRead(fd, limit)
    const after = fs.fstatSync(fd, { bigint: true })
    if (!same(fingerprint(before), fingerprint(after))) fail('File changed while reading; reopen')
    return { bytes, version: { ...fingerprint(after), sha256: sha(bytes) }, mode: Number(after.mode) & 0o777 }
  } finally { fs.closeSync(fd) }
}
function text(bytes) {
  const result = new TextDecoder('utf-8', { fatal: true }).decode(bytes)
  if (/[\x00-\x08\x0b\x0c\x0e-\x1f]/.test(result)) fail('Binary data cannot be edited as text')
  return result
}
function guarded(parent, child, action) {
  const lock = `${fdPath(parent)}/${RESERVED}lock-${sha(Buffer.from(child))}`
  const fd = fs.openSync(lock, C.O_WRONLY | C.O_CREAT | C.O_EXCL | C.O_NOFOLLOW, 0o600)
  try { return action() } finally { fs.closeSync(fd); fs.unlinkSync(lock) }
}
function publish(parent, child, bytes, replacing) {
  return guarded(parent, child, () => {
    let current
    if (replacing) {
      current = snapshot(parent, child)
      if (!same(current.version, replacing)) fail('File changed since preview; reopen before saving')
    }
    const temp = `${fdPath(parent)}/${RESERVED}tmp-${crypto.randomBytes(16).toString('hex')}`
    try {
      const fd = fs.openSync(temp, C.O_WRONLY | C.O_CREAT | C.O_EXCL | C.O_NOFOLLOW, 0o600)
      try { fs.writeFileSync(fd, bytes); if (current) fs.fchmodSync(fd, current.mode); fs.fsyncSync(fd) } finally { fs.closeSync(fd) }
      if (replacing) {
        if (!same(snapshot(parent, child).version, replacing)) fail('File changed before commit; edit not saved')
        fs.renameSync(temp, entryPath(parent, child))
      } else {
        // link is an atomic no-overwrite publication, unlike rename on an existing target.
        fs.linkSync(temp, entryPath(parent, child)); fs.unlinkSync(temp)
      }
      fs.fsyncSync(parent)
    } finally {
      try { fs.unlinkSync(temp) } catch (error) { if (error.code !== 'ENOENT') throw error }
    }
    return { ok: true }
  })
}

/** Each request holds every ancestor directory open without following user-controlled links. */
export function createWorkspaceApi(home = '/home/dsh') {
  return async function execute(request, input = Buffer.alloc(0), binaryOutput = () => {}) {
    const absolute = typeof request.workspace === 'string' ? request.workspace : ''
    if (absolute !== home && !absolute.startsWith(home + '/')) fail('Workspace must be within the runtime home')
    const workspace = absolute === home ? [] : parts(absolute.slice(home.length + 1))
    const held = []; let parent
    try {
      let current = fs.openSync('/', DIRECTORY); held.push(current)
      for (const component of [...home.split('/').filter(Boolean), ...workspace, ...parts(request.directory ?? '')]) {
        const next = fs.openSync(`${fdPath(current)}/${name(component)}`, DIRECTORY); held.push(next); current = next
      }
      parent = current
      switch (request.op) {
        case 'list': {
          const entries = []; const dir = fs.opendirSync(fdPath(parent))
          try {
            let item
            while ((item = dir.readSync()) !== null) {
              if (item.name.startsWith(RESERVED)) continue
              if (entries.length >= 10000) fail('Directory exceeds the 10000 entry listing limit')
              try {
                const stat = lstat(entryPath(parent, item.name))
                entries.push({ name: item.name, kind: stat.isSymbolicLink() ? 'link' : stat.isDirectory() ? 'directory' : stat.isFile() ? 'file' : 'special', size: `${stat.size}`, version: fingerprint(stat) })
              } catch (error) { if (error.code !== 'ENOENT') throw error }
            }
          } finally { dir.closeSync() }
          entries.sort((a, b) => a.name < b.name ? -1 : a.name > b.name ? 1 : 0)
          const page = request.page ?? 0
          if (!Number.isInteger(page) || page < 0 || page > 100) fail('Invalid page')
          return { entries: entries.slice(page * 100, (page + 1) * 100), page, total: entries.length }
        }
        case 'read': {
          const file = snapshot(parent, request.name)
          return { text: text(file.bytes), version: file.version }
        }
        case 'write': {
          if (input.length > TEXT_LIMIT) fail('Text exceeds 1 MiB')
          text(input)
          if (!request.version || typeof request.version.sha256 !== 'string') fail('A preview version is required')
          return publish(parent, name(request.name), input, request.version)
        }
        case 'import':
          if (input.length > FILE_LIMIT) fail('Import exceeds 32 MiB')
          return publish(parent, name(request.name), input)
        case 'create':
          return publish(parent, name(request.name), Buffer.alloc(0))
        case 'mkdir':
          fs.mkdirSync(entryPath(parent, request.name), { mode: 0o700 }); fs.fsyncSync(parent); return { ok: true }
        case 'delete': {
          return guarded(parent, request.name, () => {
            const target = entryPath(parent, request.name); const stat = lstat(target)
            if (!same(fingerprint(stat), request.version)) fail('Entry changed since listing; refresh first')
            if (stat.isDirectory()) fs.rmdirSync(target); else fs.unlinkSync(target)
            fs.fsyncSync(parent); return { ok: true }
          })
        }
        case 'move': {
          const destination = parts(request.destination)
          if (!destination.length) fail('Destination must include a new name')
          const newName = destination.pop(); let targetParent = held[held.length - 1 - parts(request.directory ?? '').length]
          for (const component of destination) { targetParent = fs.openSync(`${fdPath(targetParent)}/${name(component)}`, DIRECTORY); held.push(targetParent) }
          return guarded(parent, request.name, () => guarded(targetParent, newName, () => {
            const source = entryPath(parent, request.name); const stat = lstat(source)
            if (stat.isDirectory()) fail('Directory moves are not supported; move files individually')
            if (!stat.isFile() && !stat.isSymbolicLink()) fail('Special entries cannot be moved')
            if (!same(fingerprint(stat), request.version)) fail('Entry changed since listing; refresh first')
            const target = entryPath(targetParent, newName)
            fs.linkSync(source, target)
            const linked = lstat(target)
            try {
              if (`${linked.dev}` !== `${stat.dev}` || `${linked.ino}` !== `${stat.ino}`) fail('Source changed during move')
              const latest = lstat(source)
              if (`${latest.dev}` !== `${stat.dev}` || `${latest.ino}` !== `${stat.ino}`) fail('Source changed before unlink')
              fs.unlinkSync(source); fs.fsyncSync(parent); fs.fsyncSync(targetParent); return { ok: true }
            } catch (error) {
              const current = lstat(target)
              if (`${current.dev}` === `${linked.dev}` && `${current.ino}` === `${linked.ino}`) fs.unlinkSync(target)
              throw error
            }
          }))
        }
        case 'export': {
          const file = snapshot(parent, request.name, FILE_LIMIT)
          binaryOutput(file.bytes); return null
        }
        default: fail('Unknown workspace operation')
      }
    } finally { for (const fd of held.reverse()) fs.closeSync(fd) }
  }
}

if (process.argv[1]?.startsWith('{')) {
  try {
    const request = JSON.parse(process.argv[1]); const chunks = []; let total = 0
    if (request.op === 'write' || request.op === 'import') {
      const limit = request.op === 'write' ? TEXT_LIMIT : FILE_LIMIT
      for await (const chunk of process.stdin) { total += chunk.length; if (total > limit) fail('Input exceeds size limit'); chunks.push(chunk) }
    }
    const result = await createWorkspaceApi()(request, Buffer.concat(chunks, total), bytes => process.stdout.write(bytes))
    if (result !== null) process.stdout.write(JSON.stringify(result) + '\n')
  } catch (error) { process.stderr.write(JSON.stringify({ error: error.code ?? error.message }) + '\n'); process.exitCode = 1 }
}
