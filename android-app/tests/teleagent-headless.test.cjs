'use strict';

// All HTTP traffic terminates at this test's ephemeral loopback server. These
// tests never decrypt a desktop credential or start an installed TeleAgent.
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');
const vm = require('node:vm');
const { EventEmitter } = require('node:events');
const { PassThrough, Writable } = require('node:stream');

const asset = path.resolve(__dirname, '../app/src/main/assets/teleagent-headless-cli.js');
const auth = require('../app/src/main/assets/teleagent-auth.js');
const originalAssertOwner = auth.assertCurrentOwner;
auth.assertCurrentOwner = () => true;
const { Bridge } = require(asset);
const SESSION = 'ses_OfflineOwn';
const MESSAGE = 'msg_OfflineUser';
const ASSISTANT = 'msg_OfflineAssistant';
const FOREIGN = 'ses_OfflineForeign';
const query = '?directory=' + encodeURIComponent(path.resolve(__dirname));
const pause = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));

function textPart(text, overrides = {}) {
  return { id: 'prt_OfflineText', sessionID: SESSION, messageID: ASSISTANT, type: 'text', text, ...overrides };
}

function record(text, overrides = {}, parts) {
  return {
    info: {
      id: ASSISTANT, sessionID: SESSION, role: 'assistant', parentID: MESSAGE,
      time: { created: 1, completed: 2 }, finish: 'stop', ...overrides,
    },
    parts: parts || [textPart(text)],
  };
}

function permission(id = 'per_OfflineOwn', overrides = {}) {
  return { id, sessionID: SESSION, permission: 'edit', patterns: ['owned.txt'], metadata: {}, always: [], ...overrides };
}

function question(id = 'que_OfflineOwn', overrides = {}) {
  return {
    id, sessionID: SESSION,
    questions: [{ header: 'Choice', question: 'Choose an option', options: [
      { label: 'Alpha', description: 'First' }, { label: 'Beta', description: 'Second' },
    ], custom: false }],
    ...overrides,
  };
}

async function serverHarness(run) {
  const state = { records: [], permissions: [], questions: [], statuses: { [SESSION]: { type: 'busy' } }, calls: [], chunks: false };
  let key = crypto.randomBytes(32).toString('base64url');
  let password = crypto.randomBytes(32).toString('base64url');
  const server = http.createServer(async (request, response) => {
    try {
      const bytes = [];
      for await (const chunk of request) bytes.push(chunk);
      const raw = Buffer.concat(bytes).toString('utf8');
      const body = raw ? JSON.parse(raw) : undefined;
      const pathname = new URL(request.url, 'http://localhost').pathname;
      const timestamp = request.headers['x-sa-timestamp'];
      const nonce = request.headers['x-sa-nonce'];
      const signed = crypto.createHmac('sha256', key)
        .update(['local-v1', request.method, request.url, timestamp, nonce].join('\n')).digest('base64url');
      // The backend's readiness endpoint is deliberately public.
      if (pathname !== '/ready') {
        assert.equal(request.headers['x-sa-sign-version'], 'local-v1');
        assert.equal(request.headers['x-sa-signature'], signed, 'the exact query-bearing route must be signed');
        assert.equal(request.headers.authorization, 'Basic ' + Buffer.from('agent-control:' + password).toString('base64'));
      }
      state.calls.push({ method: request.method, route: request.url, body });
      if (state.hang?.(request, response)) return;
      let result;
      if (pathname === '/ready') result = { ready: true };
      else if (pathname === '/session' && request.method === 'POST') result = { id: SESSION };
      else if (pathname === '/session/' + SESSION + '/prompt_async') { response.writeHead(204); response.end(); return; }
      else if (pathname === '/session/' + SESSION + '/message') result = typeof state.records === 'function' ? state.records() : state.records;
      else if (pathname === '/permission') result = state.permissions;
      else if (pathname === '/question') result = state.questions;
      else if (pathname === '/session/status') result = state.statuses;
      else if (request.method === 'POST' && /^\/(permission|question)\/[^/]+\/(reply|reject)$/.test(pathname)) result = true;
      else if (pathname === '/session/' + SESSION + '/abort') result = true;
      else throw Error('Unexpected test route: ' + request.method + ' ' + pathname);
      response.setHeader('Content-Type', 'application/json');
      const data = Buffer.from(JSON.stringify(result));
      if (!state.chunks) { response.end(data); return; }
      // Single-byte writes split every multibyte UTF-8 sequence deliberately.
      for (const byte of data) {
        if (response.destroyed) break;
        response.write(Buffer.from([byte]));
        await new Promise(resolve => setImmediate(resolve));
      }
      response.end();
    } catch (error) {
      state.serverError = error;
      if (!response.headersSent) response.writeHead(500);
      response.end();
    }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const base = 'http://127.0.0.1:' + server.address().port;
  const events = [];
  const bridge = new Bridge(event => events.push(event));
  Object.assign(bridge, {
    session: SESSION, messageId: MESSAGE, query, base, key, password,
    loaded: { authState: { token: 'offline-fixture' } }, dead: true,
  });
  const controller = {
    state, events, bridge, base, port: server.address().port,
    setSigning(nextKey, nextPassword) { key = nextKey; password = nextPassword; },
    posts() { return state.calls.filter(call => call.method === 'POST'); },
  };
  try {
    await run(controller);
    if (state.serverError) throw state.serverError;
  } finally {
    auth.assertCurrentOwner = () => true;
    bridge.dead = true;
    await bridge.close();
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  }
}

const cases = [];
function test(name, run) { cases.push({ name, run }); }

test('session, parent message, and part ownership filter foreign content', async () => {
  await serverHarness(async ({ state, events, bridge }) => {
    state.records = [
      record('foreign session', { sessionID: FOREIGN }),
      record('foreign parent', { parentID: 'msg_OtherUser' }),
      record('user echo', { role: 'user' }),
      record('own 中文 🛰️', {}, [
        textPart('own 中文 🛰️'),
        textPart('foreign part session', { id: 'prt_ForeignSession', sessionID: FOREIGN }),
        textPart('foreign part message', { id: 'prt_ForeignMessage', messageID: 'msg_OtherAssistant' }),
      ]),
    ];
    state.permissions = [permission('per_Foreign', { sessionID: FOREIGN })];
    state.questions = [question('que_Foreign', { sessionID: FOREIGN })];
    await bridge.poll();
    assert.equal(events.find(event => event.type === 'teleagent.snapshot')?.text, 'own 中文 🛰️');
    assert.equal(bridge.requests.size, 0);
    assert.ok(!events.some(event => ['teleagent.permission', 'teleagent.question', 'teleagent.done'].includes(event.type)));
  });
});

test('completed tool round does not end the task before the final assistant round', async () => {
  await serverHarness(async ({ state, events, bridge }) => {
    const tool = {
      id: 'prt_Tool', sessionID: SESSION, messageID: ASSISTANT, type: 'tool', callID: 'call_Offline', tool: 'read',
      state: { status: 'completed', title: 'Read owned file', output: 'fixture', input: {} },
    };
    state.records = [record('Reading the file…', { finish: 'tool-calls' }, [textPart('Reading the file…'), tool])];
    state.statuses = { [SESSION]: { type: 'idle' } };
    await bridge.poll();
    assert.equal(bridge.finished, false, 'a completed tool round cannot be accepted as end_turn');
    assert.equal(events.filter(event => event.type === 'teleagent.done').length, 0);
    assert.equal(events.filter(event => event.type === 'teleagent.tool').length, 1);
    await bridge.poll();
    assert.equal(events.filter(event => event.type === 'teleagent.tool').length, 1, 'same tool state emits once');
    state.records.push(record('Final answer', { id: 'msg_Final' }, [textPart('Final answer', { messageID: 'msg_Final' })]));
    await bridge.poll();
    assert.equal(events.filter(event => event.type === 'teleagent.done').length, 1);
    assert.ok(events.at(-1).text.endsWith('Final answer'));
  });
});

test('running tool blocks completion even if a message has a completed timestamp', async () => {
  await serverHarness(async ({ state, bridge }) => {
    state.records = [record('Waiting for tool', {}, [textPart('Waiting for tool'), {
      id: 'prt_Running', sessionID: SESSION, messageID: ASSISTANT, type: 'tool', callID: 'call_Running', tool: 'read',
      state: { status: 'running', input: {}, time: { start: 1 } },
    }])];
    state.statuses = {};
    await bridge.poll();
    assert.equal(bridge.finished, false);
  });
});

test('UTF-8 byte boundaries preserve Chinese and emoji through snapshot and done', async () => {
  await serverHarness(async ({ state, bridge, events }) => {
    const answer = '计算：47 × 89 = 4183。完成 🛰️ 中文。TA_OFFLINE';
    state.chunks = true;
    state.records = [record(answer)];
    state.statuses = { [SESSION]: { type: 'idle' } };
    await bridge.poll();
    assert.equal(events.find(event => event.type === 'teleagent.snapshot')?.text, answer);
    assert.equal(events.find(event => event.type === 'teleagent.done')?.text, answer);
  });
});

test('permission reply permits once/reject, rejects escalation, and never submits twice', async () => {
  await serverHarness(async ({ state, bridge, posts }) => {
    state.permissions = [permission('per_Once'), permission('per_Reject')];
    await bridge.poll();
    for (const reply of ['always', 'session', 'allow', undefined]) {
      await assert.rejects(bridge.answer({ op: 'permission', sessionId: SESSION, requestId: 'per_Once', reply }), /权限选项/);
    }
    await assert.rejects(bridge.answer({ op: 'permission', sessionId: FOREIGN, requestId: 'per_Once', reply: 'once' }), /会话/);
    await bridge.answer({ op: 'permission', sessionId: SESSION, requestId: 'per_Once', reply: 'once' });
    await assert.rejects(bridge.answer({ op: 'permission', sessionId: SESSION, requestId: 'per_Once', reply: 'once' }), /已处理|已失效/);
    await bridge.answer({ op: 'permission', sessionId: SESSION, requestId: 'per_Reject', reply: 'reject' });
    assert.deepEqual(posts().map(call => call.body), [{ reply: 'once' }, { reply: 'reject' }]);
    assert.ok(posts().every(call => call.route.endsWith(query)));
  });
});

test('questions validate option labels, multiplicity, default custom answers, rejection, and deduplication', async () => {
  await serverHarness(async ({ state, bridge, posts }) => {
    state.questions = [
      question('que_Select'),
      question('que_Custom', { questions: [{ header: 'Free', question: 'Answer freely', options: [] }] }),
      question('que_Multiple', { questions: [{ header: 'Multi', question: 'Pick two', multiple: true, custom: false,
        options: [{ label: 'Alpha', description: '' }, { label: 'Beta', description: '' }] }] }),
      question('que_Reject'),
    ];
    await bridge.poll();
    await assert.rejects(bridge.answer({ op: 'question', sessionId: SESSION, requestId: 'que_Select', answers: [['Other']] }), /问答内容/);
    await assert.rejects(bridge.answer({ op: 'question', sessionId: SESSION, requestId: 'que_Select', answers: [['Alpha', 'Beta']] }), /问答内容/);
    await assert.rejects(bridge.answer({ op: 'question', sessionId: SESSION, requestId: 'que_Select', answers: [] }), /问答内容/);
    await bridge.answer({ op: 'question', sessionId: SESSION, requestId: 'que_Select', answers: [['Alpha']] });
    await assert.rejects(bridge.answer({ op: 'question', sessionId: SESSION, requestId: 'que_Select', answers: [['Beta']] }), /已处理|已失效/);
    await bridge.answer({ op: 'question', sessionId: SESSION, requestId: 'que_Custom', answers: [['用户的自由文本']] });
    await bridge.answer({ op: 'question', sessionId: SESSION, requestId: 'que_Multiple', answers: [['Alpha', 'Beta']] });
    await bridge.answer({ op: 'question-reject', sessionId: SESSION, requestId: 'que_Reject' });
    assert.equal(posts().length, 4);
    assert.deepEqual(posts()[1].body, { answers: [['用户的自由文本']] });
    assert.ok(posts()[3].route.startsWith('/question/que_Reject/reject'));
    assert.equal(posts()[3].body, undefined);
  });
});

test('auth invalidation and uncertain permission submission cannot cause automatic resubmission', async () => {
  await serverHarness(async ({ state, bridge, posts }) => {
    state.permissions = [permission('per_AuthChange')];
    await bridge.poll();
    const error = Object.assign(Error('fixture owner changed'), { code: 'teleagent_owner_changed' });
    auth.assertCurrentOwner = () => { throw error; };
    await assert.rejects(bridge.answer({ op: 'permission', sessionId: SESSION, requestId: 'per_AuthChange', reply: 'once' }), error);
    assert.equal(posts().length, 0);
    auth.assertCurrentOwner = () => true;
    await assert.rejects(bridge.answer({ op: 'permission', sessionId: SESSION, requestId: 'per_AuthChange', reply: 'once' }), /已处理|已失效/);
    assert.equal(posts().length, 0);

    state.permissions = [permission('per_Unknown')];
    await bridge.poll();
    state.hang = (request, response) => {
      if (!request.url.startsWith('/permission/per_Unknown/reply')) return false;
      response.destroy();
      return true;
    };
    await assert.rejects(bridge.answer({ op: 'permission', sessionId: SESSION, requestId: 'per_Unknown', reply: 'reject' }), /提交状态未知/);
    state.hang = null;
    await assert.rejects(bridge.answer({ op: 'permission', sessionId: SESSION, requestId: 'per_Unknown', reply: 'reject' }), /已处理|已失效/);
    assert.equal(posts().length, 1, 'interrupted POST is never repeated');
  });
});

test('close aborts its own submitted session once and preserves unrelated sessions', async () => {
  await serverHarness(async ({ state, bridge, posts }) => {
    let kills = 0;
    bridge.dead = false;
    bridge.submitted = true;
    bridge.child = { kill() { kills++; bridge.dead = true; } };
    bridge.exited = Promise.resolve();
    await Promise.all([bridge.close(), bridge.close()]);
    assert.equal(kills, 1);
    assert.deepEqual(posts().map(call => call.route), ['/session/' + SESSION + '/abort' + query]);
    assert.ok(!state.calls.some(call => call.route.includes(FOREIGN)));
    assert.equal(bridge.key, '');
    assert.equal(bridge.loaded.authState.token, '');
  });
});

function isolatedBridge(fakeSpawn, fakeFs = fs) {
  const fakeAuth = { assertCurrentOwner() { return true; } };
  const mainModule = { exports: {} };
  const customRequire = name => {
    if (name === './teleagent-auth.js') return fakeAuth;
    if (name === './teleagent-config.js') return {};
    if (name === 'child_process') return { spawn: fakeSpawn };
    if (name === 'fs') return fakeFs;
    return require(name);
  };
  const context = vm.createContext({ require: customRequire, module: mainModule,
    process: { platform: 'win32', env: { SystemRoot: 'C:\\Windows' } },
    Buffer, URL, fetch, AbortSignal, setTimeout, clearTimeout, console });
  vm.runInContext(fs.readFileSync(asset, 'utf8'), context, { filename: asset });
  return new mainModule.exports.Bridge(() => {});
}

test('Windows shutdown targets only the owned PID and waits for taskkill tree completion', async () => {
  const calls = [];
  let fallbackKills = 0, markExited;
  const bridge = isolatedBridge((command, args, options) => {
    calls.push({ command, args: [...args], options });
    const helper = new EventEmitter();
    helper.kill = () => {};
    setImmediate(() => { bridge.dead = true; markExited(); helper.emit('close', 0); });
    return helper;
  });
  bridge.child = { pid: 424242, kill() { fallbackKills++; } };
  bridge.exited = new Promise(resolve => { markExited = resolve; });
  assert.equal(await bridge.close(), null);
  assert.equal(fallbackKills, 0);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].command, path.join('C:\\Windows', 'System32', 'taskkill.exe'));
  assert.deepEqual(calls[0].args, ['/PID', '424242', '/T', '/F']);
  assert.equal(calls[0].options.windowsHide, true);
  assert.equal(calls[0].options.stdio, 'ignore');
  assert.ok(!('shell' in calls[0].options));
  assert.deepEqual(Object.keys(calls[0].options.env).sort(), ['SystemRoot', 'WINDIR']);
});

test('unavailable taskkill falls back to killing its own child', async () => {
  let kills = 0, markExited;
  const bridge = isolatedBridge(() => {
    const helper = new EventEmitter();
    helper.kill = () => {};
    setImmediate(() => helper.emit('error', Error('offline taskkill unavailable')));
    return helper;
  });
  bridge.child = { pid: 424242, kill() { kills++; bridge.dead = true; markExited(); } };
  bridge.exited = new Promise(resolve => { markExited = resolve; });
  assert.equal(await bridge.close(), null);
  assert.equal(kills, 1);
});

test('cleanup failure clears credentials and prevents a successful done event', async () => {
  const removals = [], events = [];
  const bridge = isolatedBridge(() => { throw Error('must not spawn a process'); }, {
    ...fs,
    rmSync(root, options) { removals.push({ root, options }); throw Object.assign(Error('offline locked root'), { code: 'EPERM' }); },
  });
  const root = path.join(os.tmpdir(), 'agent-control-teleagent-offline-cleanup');
  Object.assign(bridge, { session: SESSION, messageId: MESSAGE, query, dead: true,
    root, ownedRoot: path.resolve(root), loaded: { authState: { token: 'offline token' } }, key: 'offline key', password: 'offline password' });
  bridge.emit = event => events.push(event);
  bridge.request = async route => route.startsWith('/session/' + SESSION + '/message') ? [record('offline reply')]
    : route.startsWith('/session/status') ? {} : [];
  await assert.rejects(bridge.poll(), /后台清理失败/);
  assert.ok(!events.some(event => event.type === 'teleagent.done'));
  const cleanupError = await bridge.close();
  assert.match(cleanupError.message, /后台清理失败/);
  assert.equal(removals.length, 1, 'cached cleanup result must not repeat deletion or reject recursively');
  assert.equal(removals[0].root, path.resolve(root));
  assert.equal(removals[0].options.maxRetries, 3);
  assert.equal(removals[0].options.retryDelay, 100);
  assert.equal(bridge.loaded.authState.token, '');
  assert.equal(bridge.key, '');
  assert.equal(bridge.password, '');
});

async function waitUntil(predicate, label) {
  const deadline = Date.now() + 3000;
  while (Date.now() < deadline) {
    if (predicate()) return;
    await pause(10);
  }
  throw Error('Timed out waiting for ' + label);
}

async function cliStop(mode, phase = 'poll') {
  await serverHarness(async controller => {
    const { state } = controller;
    state.records = phase === 'binding' ? [] : [{ info: { id: MESSAGE, sessionID: SESSION, role: 'user', time: { created: 1 } },
      parts: [{ id: 'prt_CliPrompt', sessionID: SESSION, messageID: MESSAGE, type: 'text', text: 'Offline stop test' }] }];
    const processStub = new EventEmitter();
    processStub.platform = 'win32';
    processStub.env = { ...process.env };
    processStub.cwd = () => __dirname;
    processStub.stdin = new PassThrough();
    const emitted = [];
    let lines = '';
    processStub.stdout = new Writable({ write(chunk, encoding, callback) {
      lines += chunk.toString();
      let index;
      while ((index = lines.indexOf('\n')) >= 0) {
        emitted.push(JSON.parse(lines.slice(0, index)));
        lines = lines.slice(index + 1);
      }
      callback();
    } });
    let child;
    let kills = 0;
    let runtimeRoot;
    const fakeAuth = {
      async loadCurrentAuth() {
        if (phase === 'auth') await pause(40);
        return { root: os.tmpdir(), ownerRoot: os.tmpdir(), authState: { token: 'offline only' } };
      },
      assertCurrentOwner() { return true; },
    };
    const fakeConfig = {
      readTeleAgentConfig() {
        return { provider: {}, agent: { 'opencowork-default': {} }, default_agent: 'opencowork-default',
          model: { providerID: 'NewApi', modelID: 'offline' }, metadata: { packageVersion: 'offline' } };
      },
    };
    const fakeSpawn = () => {
      child = new EventEmitter();
      child.stdout = new PassThrough();
      child.stderr = new PassThrough();
      child.stdin = new Writable({ write(chunk, encoding, callback) {
        const payload = JSON.parse(chunk.subarray(4).toString('utf8'));
        assert.equal(chunk.readUInt32BE(0), chunk.length - 4);
        runtimeRoot = path.dirname(payload.XDG_DATA_HOME);
        controller.setSigning(payload.SUPER_AGENT_LOCAL_SESSION_KEY, payload.OPENCODE_SERVER_PASSWORD);
        callback();
      } });
      child.kill = () => { kills++; child.emit('exit', 0); };
      return child;
    };
    const fakeNet = { createServer() {
      const socket = new EventEmitter();
      socket.listen = (port, host, callback) => setImmediate(callback);
      socket.address = () => ({ port: controller.port });
      socket.close = callback => setImmediate(callback);
      return socket;
    } };
    const mainModule = { exports: {} };
    const customRequire = name => {
      if (name === './teleagent-auth.js') return fakeAuth;
      if (name === './teleagent-config.js') return fakeConfig;
      if (name === 'child_process') return { spawn: fakeSpawn };
      if (name === 'net') return fakeNet;
      if (name === 'fs' && phase === 'cleanup') return {
        ...fs,
        rmSync(root, options) {
          assert.equal(path.resolve(root), path.resolve(runtimeRoot));
          assert.equal(options.maxRetries, 3);
          throw Object.assign(Error('offline locked directory'), { code: 'EPERM' });
        },
      };
      return require(name);
    };
    customRequire.main = mainModule;
    const context = vm.createContext({ require: customRequire, module: mainModule, process: processStub,
      Buffer, URL, fetch, AbortSignal, setTimeout, clearTimeout, console });
    vm.runInContext(fs.readFileSync(asset, 'utf8'), context, { filename: asset });
    const directory = path.resolve(__dirname);
    processStub.stdin.write(JSON.stringify({ op: 'start', appPath: path.join(os.tmpdir(), 'TeleAgent.exe'),
      directory, prompt: 'Offline stop test', write: false }) + '\n');
    try {
      if (phase === 'auth') {
        if (mode === 'eof') processStub.stdin.end();
        else processStub.stdin.write(JSON.stringify({ op: 'cancel' }) + '\n');
        await pause(120);
        assert.equal(child, undefined, 'cancellation during asynchronous login must not spawn a backend later');
        assert.ok(!emitted.some(event => event.type === 'teleagent.error'), JSON.stringify(emitted));
        return;
      }
      await waitUntil(() => {
        const error = emitted.find(event => event.type === 'teleagent.error');
        if (error) throw Error(error.message);
        return state.calls.some(call => phase === 'binding'
          ? call.route.startsWith('/session/' + SESSION + '/message')
          : call.route.startsWith('/session/status'));
      }, 'first CLI poll');
      // Ensure accepted submission has returned before exercising stdin shutdown.
      await pause(20);
      if (mode === 'eof') processStub.stdin.end();
      else processStub.stdin.write(JSON.stringify({ op: 'cancel' }) + '\n');
      await waitUntil(() => kills > 0, mode + ' shutdown');
      const aborts = controller.posts().filter(call => call.route.startsWith('/session/') && call.route.includes('/abort'));
      assert.equal(aborts.length, 1);
      assert.equal(aborts[0].route, '/session/' + SESSION + '/abort?directory=' + encodeURIComponent(directory));
      assert.equal(state.calls.filter(call => call.route.includes('/prompt_async')).length, 1);
      assert.ok(!emitted.some(event => event.type === 'teleagent.done'));
      if (phase === 'binding') await pause(150);
      if (phase === 'cleanup') {
        await waitUntil(() => processStub.stdin.destroyed, 'cleanup failure stdin shutdown');
        await pause(40);
        const errors = emitted.filter(event => event.type === 'teleagent.error');
        assert.equal(errors.length, 1, JSON.stringify(emitted));
        assert.match(errors[0].message, /后台清理失败/);
        assert.equal(processStub.exitCode, 1);
      } else assert.ok(!emitted.some(event => event.type === 'teleagent.error'), JSON.stringify(emitted));
    } finally {
      processStub.stdin.end();
      // A failed lifecycle assertion must not leave even its mocked child/root.
      if (child && !kills) child.kill();
      if (runtimeRoot && path.dirname(path.resolve(runtimeRoot)) === path.resolve(os.tmpdir()) &&
          path.basename(runtimeRoot).startsWith('agent-control-teleagent-')) {
        fs.rmSync(runtimeRoot, { recursive: true, force: true });
      }
      processStub.stdin.destroy();
    }
  });
}

test('actual CLI EOF aborts only the created session', () => cliStop('eof'));
test('actual CLI cancel aborts only the created session', () => cliStop('cancel'));
test('EOF during async auth bootstrap cannot create a later orphan backend', () => cliStop('eof', 'auth'));
test('cancel during async auth bootstrap cannot create a later orphan backend', () => cliStop('cancel', 'auth'));
test('cancel during message binding aborts its own session without a false error', () => cliStop('cancel', 'binding'));

test('CLI EOF reports a cleanup failure once and always destroys stdin', () => cliStop('eof', 'cleanup'));

test('real bridge fixture keeps assistant-only text and a sanitized session identity', async () => {
  const fixtureFile = path.join(__dirname, 'teleagent-headless-events.json');
  const fixture = JSON.parse(fs.readFileSync(fixtureFile, 'utf8'));
  assert.ok(Array.isArray(fixture));
  assert.ok(fixture.length >= 3);
  assert.deepEqual(fixture[0], { type: 'teleagent.session', sessionId: 'ses_Fixture' });
  assert.ok(fixture.slice(1).every(event => ['teleagent.snapshot', 'teleagent.done'].includes(event.type) && typeof event.text === 'string' && event.sessionId === 'ses_Fixture'));
  const done = fixture.filter(event => event.type === 'teleagent.done');
  assert.equal(done.length, 1);
  assert.match(done[0].text, /4183/);
  await serverHarness(async ({ state, bridge, events }) => {
    state.records = [record(done[0].text)];
    state.statuses = {};
    await bridge.poll();
    assert.deepEqual(events.filter(event => ['teleagent.snapshot', 'teleagent.done'].includes(event.type))
      .map(({ type, text }) => ({ type, text })), [
        { type: 'teleagent.snapshot', text: done[0].text },
        { type: 'teleagent.done', text: done[0].text },
      ]);
  });
});

(async () => {
  let failures = 0;
  try {
    for (const item of cases) {
      try { await item.run(); console.log('PASS ' + item.name); }
      catch (error) { failures++; console.error('FAIL ' + item.name + '\n' + (error.stack || error)); }
    }
  } finally {
    auth.assertCurrentOwner = originalAssertOwner;
  }
  console.log(`TeleAgent headless protocol: ${cases.length - failures}/${cases.length} passed`);
  if (failures) process.exitCode = 1;
})();
