import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { createWorkspaceApi } from '../app/src/main/assets/workspace-ops.mjs'

const linux = process.platform === 'linux'
async function fixture(action) {
  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'dsh-workspace-'))
  const workspace = path.join(home, 'project'); fs.mkdirSync(workspace)
  try { await action({ home, workspace, run: (request, bytes, output) => createWorkspaceApi(home)({ workspace, directory: '', ...request }, bytes, output) }) }
  finally { fs.rmSync(home, { recursive: true, force: true }) }
}

test('lists sorted pages, reads UTF-8, and refuses stale edits', { skip: !linux }, () => fixture(async ({ workspace, run }) => {
  fs.writeFileSync(path.join(workspace, 'z.txt'), 'last')
  fs.writeFileSync(path.join(workspace, 'a.txt'), 'hello')
  assert.deepEqual((await run({ op: 'list' })).entries.map(row => row.name), ['a.txt', 'z.txt'])
  const preview = await run({ op: 'read', name: 'a.txt' })
  await run({ op: 'write', name: 'a.txt', version: preview.version }, Buffer.from('edited'))
  assert.equal(fs.readFileSync(path.join(workspace, 'a.txt'), 'utf8'), 'edited')
  await assert.rejects(run({ op: 'write', name: 'a.txt', version: preview.version }, Buffer.from('stale')), /changed/)
  for (let i = 0; i < 105; i++) fs.writeFileSync(path.join(workspace, `page-${String(i).padStart(3, '0')}`), '')
  assert.equal((await run({ op: 'list', page: 0 })).entries.length, 100)
  assert.equal((await run({ op: 'list', page: 1 })).entries.length, 7)
}))

test('rejects traversal, linked ancestors, binary preview and oversized text', { skip: !linux }, () => fixture(async ({ home, workspace, run }) => {
  const outside = path.join(home, 'other'); fs.mkdirSync(outside); fs.writeFileSync(path.join(outside, 'secret'), 'untouched')
  fs.symlinkSync(outside, path.join(workspace, 'escape'))
  await assert.rejects(run({ op: 'list', directory: 'escape' }))
  await assert.rejects(run({ op: 'list', directory: '../other' }))
  await assert.rejects(run({ op: 'list', workspace: home + '-evil' }))
  fs.symlinkSync(path.join(outside, 'secret'), path.join(workspace, 'linked'))
  await assert.rejects(run({ op: 'read', name: 'linked' }))
  fs.writeFileSync(path.join(workspace, 'binary'), Buffer.from([0, 1, 2]))
  await assert.rejects(run({ op: 'read', name: 'binary' }), /Binary/)
  fs.writeFileSync(path.join(workspace, 'large'), Buffer.alloc(1024 * 1024 + 1, 65))
  await assert.rejects(run({ op: 'read', name: 'large' }), /limit/)
  assert.equal(fs.readFileSync(path.join(outside, 'secret'), 'utf8'), 'untouched')
}))

test('creates without overwrite, exports exact bytes, moves and deletes a link itself', { skip: !linux }, () => fixture(async ({ home, workspace, run }) => {
  await run({ op: 'mkdir', name: 'nested' })
  await run({ op: 'import', name: 'file' }, Buffer.from('payload'))
  await assert.rejects(run({ op: 'import', name: 'file' }, Buffer.from('overwrite')))
  let exported
  await run({ op: 'export', name: 'file' }, undefined, bytes => { exported = bytes })
  assert.equal(exported.toString(), 'payload')
  const item = (await run({ op: 'list' })).entries.find(entry => entry.name === 'file')
  await run({ op: 'move', name: 'file', destination: 'nested/renamed', version: item.version })
  assert.equal(fs.readFileSync(path.join(workspace, 'nested/renamed'), 'utf8'), 'payload')
  const outside = path.join(home, 'target'); fs.writeFileSync(outside, 'target')
  fs.symlinkSync(outside, path.join(workspace, 'link'))
  let link = (await run({ op: 'list' })).entries.find(entry => entry.name === 'link')
  await run({ op: 'move', name: 'link', destination: 'moved-link', version: link.version })
  assert.equal(fs.lstatSync(path.join(workspace, 'moved-link')).isSymbolicLink(), true)
  link = (await run({ op: 'list' })).entries.find(entry => entry.name === 'moved-link')
  await run({ op: 'delete', name: 'moved-link', version: link.version })
  assert.equal(fs.readFileSync(outside, 'utf8'), 'target')
}))

test('holds an opened ancestor when its visible directory name is replaced', { skip: !linux }, () => fixture(async ({ home, workspace, run }) => {
  fs.mkdirSync(path.join(workspace, 'folder'))
  fs.writeFileSync(path.join(workspace, 'folder/file'), 'original')
  const outside = path.join(home, 'outside'); fs.mkdirSync(outside); fs.writeFileSync(path.join(outside, 'file'), 'must not change')
  const file = await run({ op: 'read', directory: 'folder', name: 'file' })
  const originalOpen = fs.openSync; let replaced = false
  fs.openSync = function (value, flags, ...rest) {
    const fd = originalOpen.call(this, value, flags, ...rest)
    if (!replaced && typeof value === 'string' && value.endsWith('/folder') && (flags & fs.constants.O_DIRECTORY)) {
      replaced = true
      fs.renameSync(path.join(workspace, 'folder'), path.join(workspace, 'renamed'))
      fs.symlinkSync(outside, path.join(workspace, 'folder'))
    }
    return fd
  }
  try { await run({ op: 'write', directory: 'folder', name: 'file', version: file.version }, Buffer.from('anchored edit')) }
  finally { fs.openSync = originalOpen }
  assert.equal(replaced, true)
  assert.equal(fs.readFileSync(path.join(workspace, 'renamed/file'), 'utf8'), 'anchored edit')
  assert.equal(fs.readFileSync(path.join(outside, 'file'), 'utf8'), 'must not change')
}))
