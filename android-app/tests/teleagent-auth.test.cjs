'use strict';

// All fixtures below are synthetic. No installed TeleAgent auth/config files
// are read. DPAPI only protects/unprotects this test's generated AES key.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { spawn } = require('node:child_process');
const { loadCurrentAuth, assertCurrentOwner, readActiveOwner } = require('../app/src/main/assets/teleagent-auth.js');

const TOKEN = 'TEST_ONLY_TELEAGENT_AUTH';
const OWNER = { ownerDir: 'v1_public_test', tenantId: 'public', userId: 'test', generation: 1 };
const syntheticKey = crypto.randomBytes(32);
let protectedSyntheticKey;

function protectSyntheticKey(key) {
  const script = [
    '$ErrorActionPreference = "Stop"',
    'Add-Type -AssemblyName System.Security',
    '$inputBytes = [System.IO.MemoryStream]::new()',
    '[Console]::OpenStandardInput().CopyTo($inputBytes)',
    '$encrypted = [System.Security.Cryptography.ProtectedData]::Protect($inputBytes.ToArray(), $null, [System.Security.Cryptography.DataProtectionScope]::CurrentUser)',
    '$output = [Console]::OpenStandardOutput()',
    '$output.Write($encrypted, 0, $encrypted.Length)',
    '$output.Flush()',
    '$inputBytes.Dispose()'
  ].join('; ');
  return new Promise((resolve, reject) => {
    const executable = path.join(process.env.SystemRoot || 'C:\\Windows', 'System32',
      'WindowsPowerShell', 'v1.0', 'powershell.exe');
    const child = spawn(executable, ['-NoProfile', '-NonInteractive', '-Command', script], {
      windowsHide: true, stdio: ['pipe', 'pipe', 'pipe']
    });
    const chunks = [];
    child.stdout.on('data', chunk => chunks.push(chunk));
    child.stderr.on('data', chunk => chunk.fill(0));
    child.on('error', () => reject(new Error('synthetic DPAPI setup failed')));
    child.on('close', code => code === 0 ? resolve(Buffer.concat(chunks)) :
      reject(new Error('synthetic DPAPI setup failed')));
    child.stdin.end(key);
  });
}

function makeFixture() {
  const root = fs.mkdtempSync(path.join(__dirname, 'test-data-'));
  const put = (file, value) => {
    const target = path.join(root, file);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, JSON.stringify(value));
  };
  const tokenFile = 'users/v1_public_test/app-auth/token.json';
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', syntheticKey, iv);
  const encrypted = Buffer.concat([Buffer.from('v10'), iv, cipher.update(TOKEN, 'utf8'), cipher.final(), cipher.getAuthTag()]);
  const storedToken = { version: 1, encrypted: true, scheme: 'safe_storage', encryptedToken: encrypted.toString('base64') };
  put('owner-scope-ready.json', { version: 1, status: 'ready' });
  put('current-owner.json', OWNER);
  put(tokenFile, storedToken);
  put('app-auth/device-meta.json', { deviceId: 'test-device', installId: 'test-install' });
  put('Local State', { os_crypt: { encrypted_key: Buffer.concat([Buffer.from('DPAPI'), protectedSyntheticKey]).toString('base64') } });
  const options = { root, installGenerationFile: path.join(root, 'missing-install-generation') };
  const cleanup = () => {
    const resolved = path.resolve(root);
    const parent = path.resolve(__dirname);
    if (path.dirname(resolved) !== parent || !path.basename(resolved).startsWith('test-data-')) {
      throw new Error('Refusing test cleanup outside the fixture directory');
    }
    fs.rmSync(resolved, { recursive: true, force: true });
  };
  return { root, put, options, tokenFile, storedToken, cleanup };
}

test('Windows auth uses only synthetic fixtures', { skip: process.platform !== 'win32' }, async t => {
  protectedSyntheticKey = await protectSyntheticKey(syntheticKey);
  t.after(() => { syntheticKey.fill(0); protectedSyntheticKey.fill(0); });

  await t.test('DPAPI and AES-GCM synthetic roundtrip', async t => {
    const f = makeFixture(); t.after(f.cleanup);
    const loaded = await loadCurrentAuth(f.options);
    assert.equal(loaded.authState.token, TOKEN);
    assert.equal(loaded.authState.deviceId, 'test-device');
    assert.equal(assertCurrentOwner(loaded, f.options), true);
  });

  await t.test('logout removes token while retaining owner pointer', async t => {
    const f = makeFixture(); t.after(f.cleanup);
    const loaded = await loadCurrentAuth(f.options);
    fs.unlinkSync(path.join(f.root, f.tokenFile));
    assert.throws(() => assertCurrentOwner(loaded, f.options), { code: 'teleagent_owner_token_changed' });
  });

  await t.test('credential rotation stops use of the old plaintext token', async t => {
    const f = makeFixture(); t.after(f.cleanup);
    const loaded = await loadCurrentAuth(f.options);
    f.put(f.tokenFile, { ...f.storedToken, encryptedToken: Buffer.from('v10different-test-value').toString('base64') });
    assert.throws(() => assertCurrentOwner(loaded, f.options), { code: 'teleagent_owner_token_changed' });
  });

  await t.test('owner generation or identity switch stops auth reuse', async t => {
    const f = makeFixture(); t.after(f.cleanup);
    const loaded = await loadCurrentAuth(f.options);
    f.put('current-owner.json', { ...OWNER, generation: 2 });
    assert.throws(() => assertCurrentOwner(loaded, f.options), { code: 'teleagent_owner_changed' });
    f.put('current-owner.json', { ownerDir: 'v1_public_other', tenantId: 'public', userId: 'other', generation: 2 });
    f.put('users/v1_public_other/app-auth/token.json', f.storedToken);
    assert.throws(() => assertCurrentOwner(loaded, f.options), { code: 'teleagent_owner_changed' });
  });

  await t.test('relogin, forced relogin and pending owner transition are rejected', async t => {
    const f = makeFixture(); t.after(f.cleanup);
    const loaded = await loadCurrentAuth(f.options);
    const marker = 'users/v1_public_test/app-auth/relogin-required';
    f.put(marker, {});
    assert.throws(() => assertCurrentOwner(loaded, f.options), { code: 'teleagent_owner_relogin_required' });
    fs.unlinkSync(path.join(f.root, marker));
    f.put('app-auth/force-relogin.pending', {});
    assert.throws(() => assertCurrentOwner(loaded, f.options), { code: 'teleagent_force_relogin_pending' });
    fs.unlinkSync(path.join(f.root, 'app-auth/force-relogin.pending'));
    f.put('current-owner.json', { ...OWNER, pendingOwner: OWNER });
    assert.throws(() => readActiveOwner(f.options), { code: 'teleagent_owner_transition_pending' });
  });

  await t.test('install generation mismatch rejects old credentials', async t => {
    const f = makeFixture(); t.after(f.cleanup);
    const file = path.join(f.root, 'install-generation');
    fs.writeFileSync(file, 'fresh:test-generation');
    f.put('app-auth/install-receipt.json', { generation: 'fresh:earlier-generation' });
    assert.throws(() => readActiveOwner({ ...f.options, installGenerationFile: file }),
      { code: 'teleagent_install_generation_changed' });
  });

  await t.test('invalid owner paths and non-regular JSON files are rejected', async t => {
    const f = makeFixture(); t.after(f.cleanup);
    f.put('current-owner.json', { ...OWNER, ownerDir: '../outside', userId: '../outside' });
    assert.throws(() => readActiveOwner(f.options), { code: 'teleagent_owner_pointer_invalid' });
    f.put('current-owner.json', OWNER);
    const marker = path.join(f.root, 'owner-scope-ready.json');
    fs.unlinkSync(marker);
    fs.mkdirSync(marker);
    assert.throws(() => readActiveOwner(f.options), { code: 'teleagent_owner_scope_not_ready' });
  });

  await t.test('oversized JSON is rejected before parsing', async t => {
    const f = makeFixture(); t.after(f.cleanup);
    fs.writeFileSync(path.join(f.root, 'current-owner.json'), Buffer.alloc(1024 * 1024 + 1, 32));
    assert.throws(() => readActiveOwner(f.options), { code: 'teleagent_owner_pointer_missing' });
  });

  await t.test('symbolic directory cannot redirect to another owner', async t => {
    const f = makeFixture(); t.after(f.cleanup);
    const ownerDir = path.join(f.root, 'users/v1_public_test');
    const moved = path.join(f.root, 'users/moved-test-owner');
    fs.renameSync(ownerDir, moved);
    fs.symlinkSync(moved, ownerDir, 'junction');
    assert.throws(() => readActiveOwner(f.options), { code: 'teleagent_owner_path_invalid' });
  });

  await t.test('DPAPI rejects fabricated ciphertext without reading desktop keys', async t => {
    const f = makeFixture(); t.after(f.cleanup);
    f.put('Local State', { os_crypt: { encrypted_key: Buffer.concat([Buffer.from('DPAPI'), Buffer.alloc(24, 1)]).toString('base64') } });
    await assert.rejects(loadCurrentAuth(f.options), { code: 'teleagent_dpapi_unprotect_failed' });
  });

  await t.test('unavailable DPAPI helper fails without a plaintext fallback', async t => {
    const f = makeFixture(); t.after(f.cleanup);
    await assert.rejects(loadCurrentAuth({ ...f.options, powershell: path.join(f.root, 'absent-powershell.exe') }),
      { code: 'teleagent_dpapi_start_failed' });
  });

  await t.test('AES-GCM rejects modified authentication tag', async t => {
    const f = makeFixture(); t.after(f.cleanup);
    const ciphertext = Buffer.from(f.storedToken.encryptedToken, 'base64');
    ciphertext[ciphertext.length - 1] ^= 1;
    f.put(f.tokenFile, { ...f.storedToken, encryptedToken: ciphertext.toString('base64') });
    await assert.rejects(loadCurrentAuth(f.options), { code: 'teleagent_token_decrypt_failed' });
  });
});
