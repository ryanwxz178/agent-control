'use strict';

// Static data reader only: never loads or executes TeleAgent application modules.
// The returned provider contains a package credential. Keep it in memory and
// deliver it to the child through stdin; never log or persist this object.
const fs = require('node:fs');
const path = require('node:path');

function openAsar(asarPath) {
  const fd = fs.openSync(path.resolve(asarPath), 'r');
  const prefix = Buffer.alloc(16);
  fs.readSync(fd, prefix, 0, prefix.length, 0);
  const jsonLength = prefix.readUInt32LE(12);
  if (jsonLength < 2 || jsonLength > 64 * 1024 * 1024) {
    fs.closeSync(fd);
    throw new Error('Invalid TeleAgent ASAR header');
  }
  const header = Buffer.alloc(jsonLength);
  fs.readSync(fd, header, 0, header.length, 16);
  const tree = JSON.parse(header.toString('utf8'));
  const base = 8 + prefix.readUInt32LE(4);
  function entry(name) {
    let current = tree;
    for (const part of name.split('/')) current = current?.files?.[part];
    return current;
  }
  return {
    list(directory) { return Object.keys(entry(directory)?.files || {}); },
    read(name, encoding = 'utf8') {
      const item = entry(name);
      if (!item || item.files || item.unpacked || item.link || !Number.isSafeInteger(item.size)) {
        throw new Error('Required TeleAgent package entry is unavailable: ' + name);
      }
      if (item.size > 64 * 1024 * 1024) throw new Error('TeleAgent package entry is unexpectedly large');
      const bytes = Buffer.alloc(item.size);
      const offset = base + Number(item.offset);
      if (!Number.isSafeInteger(offset)) throw new Error('Invalid TeleAgent package entry offset');
      fs.readSync(fd, bytes, 0, bytes.length, offset);
      return encoding === null ? bytes : bytes.toString(encoding);
    },
    close() { fs.closeSync(fd); },
  };
}

function requiredString(value, label) {
  if (typeof value !== 'string' || !value.trim()) throw new Error(label + ' is required');
  return value.trim();
}

function multiplyNumber(expression, fallback) {
  if (!expression) return fallback;
  if (!/^\s*\d+(?:e\d+)?(?:\s*\*\s*\d+(?:e\d+)?)*\s*$/i.test(expression)) {
    throw new Error('Unsupported TeleAgent provider timeout expression');
  }
  const result = expression.split('*').reduce((product, term) => product * Number(term.trim()), 1);
  if (!Number.isSafeInteger(result) || result <= 0) throw new Error('Invalid TeleAgent provider timeout');
  return result;
}

function packageProvider(runtime) {
  const block = runtime.match(/NewApi:\s*\{\s*id:\s*"new-api",[\s\S]{0,400}?options:\s*\{([\s\S]{0,700}?)\n\s*\}/);
  if (!block) return null;
  const key = block[1].match(/apiKey:\s*"([^"\r\n]+)"/);
  const gateways = [...new Set([...runtime.matchAll(/"(https:\/\/[^"\r\n]+\/superCowork\/sapi)"/g)].map(match => match[1]))];
  const route = runtime.match(/"X-Route-Target":\s*"([^"\r\n]+)"/);
  if (!key || gateways.length !== 1 || !route || !/useSuperAgentAuth:\s*!0/.test(block[1])) return null;
  return {
    gateway: gateways[0],
    options: {
      apiKey: key[1],
      useSuperAgentAuth: true,
      timeout: multiplyNumber(block[1].match(/\btimeout:\s*([^,\r\n]+)/)?.[1], 1200000),
      chunkTimeout: multiplyNumber(block[1].match(/\bchunkTimeout:\s*([^,\r\n]+)/)?.[1], 900000),
    },
    headers: { 'X-Route-Target': route[1] },
  };
}

function readOptionalJson(file) {
  try { return JSON.parse(fs.readFileSync(file, 'utf8')); }
  catch (error) {
    if (error.code === 'ENOENT') return null;
    throw new Error('Could not parse TeleAgent metadata: ' + path.basename(file));
  }
}

function readDebugBaseURL(deviceRoot) {
  if (!deviceRoot) return undefined;
  const file = path.join(deviceRoot, 'internal-debug.yaml');
  let text;
  try { text = fs.readFileSync(file, 'utf8'); }
  catch (error) { if (error.code === 'ENOENT') return undefined; throw error; }
  const line = text.match(/^llmBaseUrl:\s*([^\r\n]*)/m)?.[1]?.trim();
  if (!line || line === 'null' || line.startsWith('#')) return undefined;
  if (line.startsWith('"')) {
    try { return JSON.parse(line.replace(/\s+#.*$/, '')); }
    catch { throw new Error('Unsupported TeleAgent llmBaseUrl YAML scalar; supply llmBaseUrl explicitly'); }
  }
  if (line.startsWith("'")) {
    const match = line.match(/^'((?:[^']|'')*)'\s*(?:#.*)?$/);
    if (!match) throw new Error('Unsupported TeleAgent llmBaseUrl YAML scalar');
    return match[1].replace(/''/g, "'");
  }
  return line.replace(/\s+#.*$/, '').trim();
}

function modelFromCatalog(item, contextLength) {
  if (!item || typeof item.modelID !== 'string' || !item.modelID.trim()) return null;
  const limits = item.limits || {};
  if (!(Number.isFinite(limits.context) && Number.isFinite(limits.output))) return null;
  const override = Number.isFinite(contextLength) && contextLength > 0 ? contextLength : undefined;
  const model = {
    id: item.modelID.trim(),
    name: typeof item.displayName === 'string' ? item.displayName : item.modelID,
    limit: {
      context: override || limits.context,
      output: limits.output,
      ...(override ? { input: override } : Number.isFinite(limits.input) ? { input: limits.input } : {}),
    },
  };
  const behavior = item.behavior || {};
  const options = {};
  for (const [source, target] of [
    ['toolCall', 'tool_call'], ['temperature', 'temperature'], ['reasoning', 'reasoning'],
    ['attachment', 'attachment'], ['toolStream', 'tool_stream'],
  ]) if (behavior[source] === true) options[target] = true;
  if (typeof behavior.interleavedField === 'string') options.interleaved = { field: behavior.interleavedField };
  if (typeof behavior.thinking === 'string') options.extra_body = { thinking: { type: behavior.thinking } };
  if (typeof behavior.reasoningEffort === 'string') options.reasoning_effort = behavior.reasoningEffort;
  if (Object.keys(options).length) model.options = options;
  return model;
}

function readCatalog(ownerRoot, presetContextLength) {
  const saved = readOptionalJson(path.join(ownerRoot, 'model-catalog.json'));
  const catalog = saved?.state?.catalog || saved?.catalog || saved;
  if (!Array.isArray(catalog?.groups)) throw new Error('Current TeleAgent account has no usable model catalog');
  const contextState = readOptionalJson(path.join(ownerRoot, 'preset-context.json'));
  const savedContext = contextState?.state?.contextLength ?? contextState?.contextLength;
  const selectedContext = presetContextLength ?? savedContext;
  const models = {};
  const defaults = [];
  for (const group of catalog.groups) {
    if (!Array.isArray(group.models)) continue;
    for (const item of group.models) {
      const model = modelFromCatalog(item, group.key === 'preset' ? selectedContext : undefined);
      if (!model) continue;
      if (models[model.id] && JSON.stringify(models[model.id]) !== JSON.stringify(model)) {
        throw new Error('Ambiguous TeleAgent catalog model definition: ' + model.id);
      }
      models[model.id] = model;
      if (item.isDefault === true) defaults.push({ id: model.id, preset: group.key === 'preset' });
    }
  }
  const chosen = defaults.filter(model => model.preset);
  const eligible = chosen.length ? chosen : defaults;
  if (eligible.length !== 1) throw new Error('TeleAgent account model catalog has no unambiguous default model');
  return { models, defaultModelID: eligible[0].id, version: catalog.version };
}

function sameGateway(base, official) {
  const a = new URL(base);
  const b = new URL(official);
  return a.origin === b.origin && (a.pathname === b.pathname || a.pathname.startsWith(b.pathname.replace(/\/$/, '') + '/'));
}

/**
 * readTeleAgentConfig({ asarPath, ownerRoot, deviceRoot?, llmBaseUrl?,
 *   bgEnv?, runtimeEnv?, presetContextLength?, readPolicy?, writePolicy? })
 *
 * ownerRoot is the selected account's data directory, not the adapter's isolated
 * runtime root. Reads only model-catalog/preset-context metadata from that root.
 * Never reads auth-state or desktop session history.
 */
function readTeleAgentConfig(options) {
  const ownerRoot = path.resolve(requiredString(options?.ownerRoot, 'ownerRoot'));
  const asar = openAsar(requiredString(options?.asarPath, 'asarPath'));
  try {
    const pkg = JSON.parse(asar.read('package.json'));
    if (pkg.name !== 'TeleAgent') throw new Error('Expected a TeleAgent application package');
    const names = asar.list('dist-electron').filter(name => /^main-runtime-.*\.js$/.test(name)).sort();
    const candidates = names.map(name => ({ name, provider: packageProvider(asar.read('dist-electron/' + name)) })).filter(item => item.provider);
    if (!candidates.length) throw new Error('Installed TeleAgent NewApi bootstrap format is unsupported');
    const signature = JSON.stringify(candidates[0].provider);
    if (candidates.some(item => JSON.stringify(item.provider) !== signature)) {
      throw new Error('Installed TeleAgent runtime archives disagree on NewApi bootstrap; cannot select safely');
    }
    // main-loader.cjs executes bytecode. A historical runtime filename cannot
    // be proved from that loader. Verify the consensus against current bytecode.
    const compiled = asar.read('dist-electron/main.jsc', 'latin1');
    const source = candidates[0].provider;
    if (!compiled.includes(source.gateway) || !compiled.includes(source.options.apiKey) || !compiled.includes('useSuperAgentAuth')) {
      throw new Error('TeleAgent NewApi bootstrap does not match the installed main bytecode');
    }
    const html = asar.read('dist/index.html');
    const rendererFile = html.match(/<script[^>]+src="\.\/(assets\/[^"<>]+\.js)"/)?.[1];
    if (!rendererFile) throw new Error('Could not identify the active TeleAgent renderer');
    const renderer = asar.read('dist/' + rendererFile);
    const agentName = renderer.match(/using default agent"\),"([A-Za-z0-9_-]+)"/)?.[1];
    if (!agentName) throw new Error('Could not identify the TeleAgent default agent');
    const catalog = readCatalog(ownerRoot, options.presetContextLength);
    const llmRoot = (options.llmBaseUrl ?? readDebugBaseURL(options.deviceRoot) ?? source.gateway).trim().replace(/\/+$/, '');
    const url = new URL(llmRoot);
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) throw new Error('Unsupported TeleAgent model relay URL');
    const headers = { ...source.headers };
    if (sameGateway(llmRoot, source.gateway)) {
      const environment = options.bgEnv ?? process.env.BG_ENV;
      if (typeof environment === 'string' && environment.trim()) headers.teleagent = environment.trim();
      if (typeof options.runtimeEnv === 'string' && options.runtimeEnv.trim()) headers['X-Runtime-Env'] = options.runtimeEnv.trim();
    }
    for (const [label, value] of [['readPolicy', options.readPolicy], ['writePolicy', options.writePolicy]]) {
      if (value !== undefined && !['allow', 'ask', 'deny'].includes(value)) throw new Error('Invalid ' + label);
    }
    const provider = {
      NewApi: {
        id: 'new-api',
        name: '预置模型',
        options: { baseURL: llmRoot + '/api/v1', ...source.options, headers },
        models: catalog.models,
      },
    };
    const agent = {
      [agentName]: {
        name: agentName,
        mode: 'primary',
        model: 'NewApi/' + catalog.defaultModelID,
        prompt: '你是 TeleAgent 助手。根据用户任务开展对话，只操作用户授权的工作目录。执行写入、命令或工作目录之外的操作时遵循权限确认。遇到需要用户决定的问题时使用 question 工具。',
        permission: {
          '*': 'ask',
          read: options.readPolicy ?? 'allow',
          glob: 'allow', grep: 'allow', list: 'allow',
          edit: options.writePolicy ?? 'ask',
          bash: process.platform === 'win32' ? 'deny' : 'ask',
          powershell: process.platform === 'win32' ? 'ask' : 'deny',
          external_directory: 'ask',
          question: 'allow',
        },
      },
    };
    return {
      provider, agent,
      default_agent: agentName,
      model: { providerID: 'NewApi', modelID: catalog.defaultModelID },
      metadata: {
        packageVersion: pkg.version,
        rendererFile: 'dist/' + rendererFile,
        runtimeSelection: 'package-consensus-confirmed-in-main-bytecode',
        runtimeCandidateCount: candidates.length,
        catalogVersion: catalog.version,
        modelCount: Object.keys(catalog.models).length,
      },
    };
  } finally {
    asar.close();
  }
}

module.exports = { readTeleAgentConfig, modelFromCatalog, openAsar };
