'use strict';

// TeleAgent's Electron AppAuthService stores the current owner's login token
// with safeStorage. Read it under the current Windows user and keep decrypted
// material inside this process. Do not log, serialize or persist the return value.
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');
const { spawn } = require('node:child_process');
const MAX_JSON_BYTES = 1024 * 1024;

class TeleAgentAuthError extends Error {
  constructor(code) {
    super(code);
    this.name = 'TeleAgentAuthError';
    this.code = code;
  }
}

function readJson(file, code) {
  let fd;
  try {
    const before = fs.lstatSync(file);
    if (!before.isFile() || before.isSymbolicLink() || before.size < 1 || before.size > MAX_JSON_BYTES) {
      throw new TeleAgentAuthError(code);
    }
    fd = fs.openSync(file, 'r');
    const opened = fs.fstatSync(fd);
    if (!opened.isFile() || opened.size !== before.size || opened.dev !== before.dev || opened.ino !== before.ino) {
      throw new TeleAgentAuthError(code);
    }
    // Bound the read even if another process expands the file after fstat.
    const buffer = Buffer.alloc(before.size + 1);
    let length = 0;
    while (length < buffer.length) {
      const count = fs.readSync(fd, buffer, length, buffer.length - length, length);
      if (!count) break;
      length += count;
    }
    const after = fs.lstatSync(file);
    if (length !== before.size || !after.isFile() || after.isSymbolicLink() ||
        after.dev !== before.dev || after.ino !== before.ino || after.size !== before.size ||
        after.mtimeMs !== before.mtimeMs || after.ctimeMs !== before.ctimeMs) {
      throw new TeleAgentAuthError(code);
    }
    return JSON.parse(buffer.subarray(0, length).toString('utf8'));
  } catch {
    throw new TeleAgentAuthError(code);
  } finally {
    if (fd !== undefined) fs.closeSync(fd);
  }
}

function getRoot(options = {}) {
  // Installed Windows desktop main-DnRKEIXG.js uses this exact device root.
  return path.resolve(options.root || path.join(os.homedir(), '.local', 'share', 'TeleAgent'));
}

function validateInstallGeneration(root, options) {
  if (fs.existsSync(path.join(root, 'app-auth', 'force-relogin.pending'))) {
    throw new TeleAgentAuthError('teleagent_force_relogin_pending');
  }
  const generationFile = options.installGenerationFile ||
    (process.env.PROGRAMDATA && path.join(process.env.PROGRAMDATA, 'TeleAgent', 'install-generation'));
  if (!generationFile || !fs.existsSync(generationFile)) return;
  let generation;
  try {
    generation = fs.readFileSync(generationFile, 'utf8').trim();
  } catch {
    throw new TeleAgentAuthError('teleagent_install_generation_unreadable');
  }
  if (!generation) return;
  const receipt = readJson(path.join(root, 'app-auth', 'install-receipt.json'),
    'teleagent_install_receipt_missing');
  if (!receipt || receipt.generation !== generation) {
    throw new TeleAgentAuthError('teleagent_install_generation_changed');
  }
}

function readActiveOwner(options = {}) {
  const root = getRoot(options);
  validateInstallGeneration(root, options);
  const marker = readJson(path.join(root, 'owner-scope-ready.json'), 'teleagent_owner_scope_not_ready');
  if (!marker || marker.version !== 1 || marker.status !== 'ready') {
    throw new TeleAgentAuthError('teleagent_owner_scope_not_ready');
  }
  const owner = readJson(path.join(root, 'current-owner.json'), 'teleagent_owner_pointer_missing');
  if (!owner || typeof owner !== 'object' || Array.isArray(owner)) {
    throw new TeleAgentAuthError('teleagent_owner_pointer_invalid');
  }
  const validUserId = typeof owner.userId === 'string' && /^[A-Za-z0-9._-]{1,128}$/.test(owner.userId);
  if (owner.tenantId !== 'public' || !validUserId ||
      owner.ownerDir !== `v1_public_${owner.userId.trim()}` ||
      !Number.isSafeInteger(owner.generation) || owner.generation < 1) {
    throw new TeleAgentAuthError('teleagent_owner_pointer_invalid');
  }
  if (owner.pendingOwner !== undefined) {
    throw new TeleAgentAuthError('teleagent_owner_transition_pending');
  }
  const ownerRoot = path.join(root, 'users', owner.ownerDir);
  if (path.dirname(ownerRoot) !== path.join(root, 'users')) {
    throw new TeleAgentAuthError('teleagent_owner_path_invalid');
  }
  for (const directory of [root, path.join(root, 'users'), ownerRoot, path.join(ownerRoot, 'app-auth')]) {
    try {
      const stat = fs.lstatSync(directory);
      if (!stat.isDirectory() || stat.isSymbolicLink()) {
        throw new TeleAgentAuthError('teleagent_owner_path_invalid');
      }
    } catch {
      throw new TeleAgentAuthError('teleagent_owner_path_invalid');
    }
  }
  if (fs.existsSync(path.join(ownerRoot, 'app-auth', 'relogin-required'))) {
    throw new TeleAgentAuthError('teleagent_owner_relogin_required');
  }
  return {
    root,
    ownerRoot,
    owner: { ownerDir: owner.ownerDir, tenantId: owner.tenantId, userId: owner.userId, generation: owner.generation }
  };
}

function assertCurrentOwner(loaded, options = {}) {
  const current = readActiveOwner({ ...options, root: loaded.root });
  if (current.owner.ownerDir !== loaded.owner.ownerDir ||
      current.owner.generation !== loaded.owner.generation) {
    throw new TeleAgentAuthError('teleagent_owner_changed');
  }
  if (typeof loaded.tokenFingerprint !== 'string' || !loaded.tokenFingerprint) {
    throw new TeleAgentAuthError('teleagent_loaded_auth_invalid');
  }
  const stored = readJson(path.join(current.ownerRoot, 'app-auth', 'token.json'),
    'teleagent_owner_token_changed');
  if (!isStoredTokenValid(stored) || tokenFingerprint(stored) !== loaded.tokenFingerprint) {
    throw new TeleAgentAuthError('teleagent_owner_token_changed');
  }
  return true;
}

function isStoredTokenValid(stored) {
  return stored && typeof stored === 'object' && stored.version === 1 &&
    stored.scheme === 'safe_storage' && stored.encrypted === true &&
    typeof stored.encryptedToken === 'string' && Boolean(stored.encryptedToken);
}

function tokenFingerprint(stored) {
  return crypto.createHash('sha256').update(stored.encryptedToken).digest('hex');
}

const DPAPI_SCRIPT = [
  '$ErrorActionPreference = "Stop"',
  'Add-Type -AssemblyName System.Security',
  '$inputBytes = [System.IO.MemoryStream]::new()',
  '[Console]::OpenStandardInput().CopyTo($inputBytes)',
  '$plainBytes = [System.Security.Cryptography.ProtectedData]::Unprotect($inputBytes.ToArray(), $null, [System.Security.Cryptography.DataProtectionScope]::CurrentUser)',
  '$outputStream = [Console]::OpenStandardOutput()',
  '$outputStream.Write($plainBytes, 0, $plainBytes.Length)',
  '$outputStream.Flush()',
  '[Array]::Clear($plainBytes, 0, $plainBytes.Length)',
  '$inputBytes.Dispose()'
].join('; ');

function unprotectCurrentUser(input, options = {}) {
  return new Promise((resolve, reject) => {
    const executable = options.powershell || path.join(process.env.SystemRoot || 'C:\\Windows',
      'System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe');
    const child = spawn(executable, ['-NoProfile', '-NonInteractive', '-Command', DPAPI_SCRIPT], {
      stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true
    });
    let buffers = [];
    let length = 0;
    let failed = false;
    const clearBuffers = () => {
      for (const buffer of buffers) buffer.fill(0);
      buffers = [];
    };
    const fail = (code) => {
      if (failed) return;
      failed = true;
      clearBuffers();
      reject(new TeleAgentAuthError(code));
    };
    const timer = setTimeout(() => {
      fail('teleagent_dpapi_timeout');
      child.kill();
    }, options.timeoutMs || 15000);
    child.stdout.on('data', chunk => {
      if (failed) { chunk.fill(0); return; }
      length += chunk.length;
      if (length > 65536) { chunk.fill(0); fail('teleagent_dpapi_output_invalid'); child.kill(); return; }
      buffers.push(chunk);
    });
    // Never relay a child error stream: diagnostics must not contain credentials.
    child.stderr.on('data', chunk => chunk.fill(0));
    child.once('error', () => fail('teleagent_dpapi_start_failed'));
    child.stdin.on('error', () => fail('teleagent_dpapi_input_failed'));
    child.once('close', code => {
      clearTimeout(timer);
      if (failed) return;
      if (code !== 0 || length !== 32) { fail('teleagent_dpapi_unprotect_failed'); return; }
      const key = Buffer.concat(buffers);
      clearBuffers();
      resolve(key);
    });
    child.stdin.end(input);
  });
}

async function loadCurrentAuth(options = {}) {
  if (process.platform !== 'win32') throw new TeleAgentAuthError('teleagent_auth_windows_only');
  const context = readActiveOwner(options);
  const stored = readJson(path.join(context.ownerRoot, 'app-auth', 'token.json'), 'teleagent_owner_token_missing');
  if (!isStoredTokenValid(stored)) {
    throw new TeleAgentAuthError('teleagent_owner_token_format_unsupported');
  }
  context.tokenFingerprint = tokenFingerprint(stored);
  const localState = readJson(path.join(context.root, 'Local State'), 'teleagent_local_state_missing');
  if (typeof localState?.os_crypt?.encrypted_key !== 'string') {
    throw new TeleAgentAuthError('teleagent_storage_key_missing');
  }
  const protectedKey = Buffer.from(localState.os_crypt.encrypted_key, 'base64');
  const encryptedToken = Buffer.from(stored.encryptedToken, 'base64');
  if (protectedKey.subarray(0, 5).toString('ascii') !== 'DPAPI' ||
      encryptedToken.subarray(0, 3).toString('ascii') !== 'v10' || encryptedToken.length <= 31) {
    throw new TeleAgentAuthError('teleagent_storage_encryption_unsupported');
  }
  let key;
  let plaintext;
  try {
    key = await unprotectCurrentUser(protectedKey.subarray(5), options);
    const decipher = crypto.createDecipheriv('aes-256-gcm', key, encryptedToken.subarray(3, 15));
    decipher.setAuthTag(encryptedToken.subarray(encryptedToken.length - 16));
    plaintext = Buffer.concat([decipher.update(encryptedToken.subarray(15, -16)), decipher.final()]);
    const token = plaintext.toString('utf8');
    if (!token || token.includes('\u0000') || !Buffer.from(token).equals(plaintext)) {
      throw new TeleAgentAuthError('teleagent_token_plaintext_invalid');
    }
    const meta = readJson(path.join(context.root, 'app-auth', 'device-meta.json'), 'teleagent_device_meta_missing');
    if (!meta || typeof meta.deviceId !== 'string' || !meta.deviceId ||
        typeof meta.installId !== 'string' || !meta.installId) {
      throw new TeleAgentAuthError('teleagent_device_meta_invalid');
    }
    // Recheck after DPAPI work in case the desktop logged out or switched owner.
    assertCurrentOwner(context, options);
    return {
      ...context,
      authState: { token, deviceId: meta.deviceId, installId: meta.installId },
      encryptionFormat: 'safe_storage/v10/windows-dpapi/aes-256-gcm'
    };
  } catch (error) {
    if (error instanceof TeleAgentAuthError) throw error;
    throw new TeleAgentAuthError('teleagent_token_decrypt_failed');
  } finally {
    key?.fill(0);
    plaintext?.fill(0);
    protectedKey.fill(0);
    encryptedToken.fill(0);
  }
}

module.exports = { loadCurrentAuth, readActiveOwner, assertCurrentOwner, TeleAgentAuthError };

if (require.main === module) {
  loadCurrentAuth().then(loaded => {
    // These fields are intentionally the only diagnostic output.
    console.log(JSON.stringify({
      tokenPresent: Boolean(loaded.authState.token),
      encryptionFormat: loaded.encryptionFormat,
      deviceMetaFields: Object.keys(loaded.authState).filter(key => key !== 'token')
    }));
    loaded.authState.token = '';
  }).catch(error => {
    console.error(JSON.stringify({ tokenPresent: false, code: error.code || 'teleagent_auth_failed' }));
    process.exitCode = 1;
  });
}
