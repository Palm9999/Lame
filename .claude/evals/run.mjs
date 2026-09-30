#!/usr/bin/env node
// Runs the cases in evals/cases against Claude Code with and without Tackle, and reports
// whether the skill fired, whether the result was correct, and what the run cost.
//
// Usage: node evals/run.mjs [--case <text>] [--runs N] [--arms with,without]
//                           [--model <model>] [--judge-model <model>] [--concurrency N] [--keep]
//
// Each case is a directory evals/cases/<skill>/<name>/ containing:
//   case.json            { "skill", "expect": "apply" | "skip", "runs"?, "max_turns"?,
//                          "timeout_seconds"?, "tools"?, "git"? }
//   prompt.md            the task given to the agent
//   fixture/             files copied into a fresh temp directory for each run
//   graders/criteria.md  rubric for the judge model, one criterion per bullet
//   check.mjs            optional; run in the work directory afterwards, exit 0 = pass

import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const evalsDir = path.dirname(fileURLToPath(import.meta.url));
const pluginDir = path.resolve(evalsDir, '..');
const casesDir = path.join(evalsDir, 'cases');

const DEFAULT_TOOLS = ['Bash', 'Read', 'Edit', 'Write', 'Glob', 'Grep', 'Skill', 'Task', 'TodoWrite'];
if (process.platform === 'win32') DEFAULT_TOOLS.push('PowerShell');

// User settings are excluded so the user's own plugins (superpowers, for example) can't
// leak into either arm; project and local settings of the temp work directory are empty.
const ISOLATION = ['--setting-sources', 'project,local', '--no-session-persistence'];

// Started from inside a Claude Code session, the runner would otherwise hand that session's
// identity (session id, messaging socket, effort level) and plugin bin directories to every run.
function cleanEnv() {
  const env = Object.fromEntries(
    Object.entries(process.env).filter(([k]) => !/^(CLAUDE|MCP_CONNECTION)/i.test(k)),
  );
  const pathKey = Object.keys(env).find((k) => k.toUpperCase() === 'PATH');
  env[pathKey] = env[pathKey]
    .split(path.delimiter)
    .filter((p) => !/[\\/]\.claude[\\/]plugins[\\/]/.test(p))
    .join(path.delimiter);
  return env;
}
const ENV = cleanEnv();

function parseArgs(argv) {
  const opts = { arms: ['with', 'without'], judgeModel: 'sonnet', concurrency: 3, keep: false };
  for (let i = 0; i < argv.length; i++) {
    const flag = argv[i];
    const value = () => argv[++i];
    if (flag === '--case') opts.case = value();
    else if (flag === '--runs') opts.runs = Number(value());
    else if (flag === '--arms') opts.arms = value().split(',');
    else if (flag === '--model') opts.model = value();
    else if (flag === '--judge-model') opts.judgeModel = value();
    else if (flag === '--concurrency') opts.concurrency = Number(value());
    else if (flag === '--keep') opts.keep = true;
    else {
      console.error(`Unknown option: ${flag}`);
      process.exit(2);
    }
  }
  return opts;
}

// Windows launches `claude` and `npm` through .cmd shims, which need a shell.
function run(cmd, args, { cwd, input = '', timeoutMs } = {}) {
  return new Promise((resolve) => {
    const win = process.platform === 'win32';
    const child = win
      ? spawn([cmd, ...args.map((a) => `"${a}"`)].join(' '), { cwd, env: ENV, shell: true })
      : spawn(cmd, args, { cwd, env: ENV });
    let stdout = '';
    let stderr = '';
    let timedOut = false;
    const timer =
      timeoutMs &&
      setTimeout(() => {
        timedOut = true;
        if (win) spawn('taskkill', ['/pid', String(child.pid), '/T', '/F']);
        else child.kill('SIGKILL');
      }, timeoutMs);
    child.stdout.setEncoding('utf8').on('data', (d) => (stdout += d));
    child.stderr.setEncoding('utf8').on('data', (d) => (stderr += d));
    child.on('close', (code) => {
      clearTimeout(timer);
      resolve({ code, stdout, stderr, timedOut });
    });
    child.stdin.end(input);
  });
}

function loadCases(filter) {
  const cases = [];
  for (const skill of subdirs(casesDir)) {
    for (const name of subdirs(path.join(casesDir, skill))) {
      const id = `${skill}/${name}`;
      if (filter && !id.includes(filter)) continue;
      const dir = path.join(casesDir, skill, name);
      const read = (...p) => fs.readFileSync(path.join(dir, ...p), 'utf8').trim();
      cases.push({
        id,
        dir,
        ...JSON.parse(read('case.json')),
        prompt: read('prompt.md'),
        criteria: read('graders', 'criteria.md'),
      });
    }
  }
  return cases;
}

function subdirs(dir) {
  return fs
    .readdirSync(dir, { withFileTypes: true })
    .filter((d) => d.isDirectory())
    .map((d) => d.name);
}

async function prepareWorkdir(c) {
  const workdir = fs.mkdtempSync(path.join(os.tmpdir(), 'tackle-eval-'));
  fs.cpSync(path.join(c.dir, 'fixture'), workdir, { recursive: true });
  if (c.git) {
    const git = (...args) => run('git', args, { cwd: workdir });
    await git('init', '-q');
    await git('add', '-A');
    await git('-c', 'user.name=eval', '-c', 'user.email=eval@example.com', 'commit', '-qm', 'Initial commit');
  }
  return workdir;
}

async function runAgent(c, arm, workdir, opts) {
  const args = [
    '-p',
    '--output-format', 'stream-json',
    '--verbose',
    ...ISOLATION,
    '--max-turns', String(c.max_turns ?? 40),
    // A fixed tool set keeps both arms comparable. The work directory is a throwaway copy,
    // so permission prompts are off rather than letting denials skew turns and outcomes.
    '--tools', (c.tools ?? DEFAULT_TOOLS).join(','),
    '--permission-mode', 'bypassPermissions',
  ];
  if (opts.model) args.push('--model', opts.model);
  if (arm === 'with') args.push('--plugin-dir', pluginDir);
  const res = await run('claude', args, {
    cwd: workdir,
    input: c.prompt,
    timeoutMs: (c.timeout_seconds ?? 600) * 1000,
  });
  const events = res.stdout
    .split('\n')
    .filter((l) => l.startsWith('{'))
    .map((l) => {
      try {
        return JSON.parse(l);
      } catch {
        return null;
      }
    })
    .filter(Boolean);
  return { ...res, events };
}

function summarise(events, skill) {
  const init = events.find((e) => e.type === 'system' && e.subtype === 'init') ?? {};
  const result = events.find((e) => e.type === 'result') ?? {};
  const skillsUsed = [];
  const lines = [];
  for (const e of events) {
    // Subagent traffic carries a parent id. It stays in the transcript, marked, because the work
    // itself may happen there: a run that delegates and ends early still has to be judged on it.
    const tag = e.parent_tool_use_id ? '[subagent] ' : '';
    if (e.type === 'assistant') {
      for (const b of e.message?.content ?? []) {
        if (b.type === 'text') lines.push(`${tag}ASSISTANT: ${b.text}`);
        if (b.type === 'tool_use') {
          if (b.name === 'Skill') skillsUsed.push(b.input?.skill);
          lines.push(`${tag}TOOL ${b.name}: ${clip(JSON.stringify(b.input), 600)}`);
        }
      }
    }
    if (e.type === 'user' && Array.isArray(e.message?.content)) {
      for (const b of e.message.content) {
        if (b.type === 'tool_result') lines.push(`${tag}RESULT: ${clip(textOf(b.content), 800)}`);
      }
    }
  }
  const u = result.usage ?? {};
  const sub = result.subagent_stats ?? {};
  return {
    plugins: (init.plugins ?? []).map((p) => p.name),
    pluginErrors: init.plugin_errors ?? [],
    memoryPaths: init.memory_paths,
    skillsUsed,
    fired: skillsUsed.some((s) => s === skill || s?.endsWith(`:${skill}`)),
    // Turns count the main thread only, so a run that hands the work to a subagent looks cheap.
    // `delegated` marks those runs so the turn comparison isn't read as like-for-like.
    subagentsSpawned: sub.spawned ?? 0,
    delegated: (sub.spawned ?? 0) > 0,
    turns: result.num_turns,
    inputTokens: (u.input_tokens ?? 0) + (u.cache_creation_input_tokens ?? 0) + (u.cache_read_input_tokens ?? 0),
    outputTokens: u.output_tokens ?? 0,
    costUsd: result.total_cost_usd ?? 0,
    durationMs: result.duration_ms,
    permissionDenials: (result.permission_denials ?? []).length,
    stopReason: result.subtype,
    finalMessage: result.result,
    transcript: lines.join('\n\n'),
  };
}

function textOf(content) {
  if (typeof content === 'string') return content;
  if (Array.isArray(content)) return content.map((b) => b.text ?? '').join('\n');
  return JSON.stringify(content);
}

function clip(s, n) {
  s = String(s ?? '');
  return s.length > n ? `${s.slice(0, n)} …[${s.length - n} more chars]` : s;
}

async function runCheck(c, workdir) {
  const script = path.join(c.dir, 'check.mjs');
  if (!fs.existsSync(script)) return null;
  const res = await run('node', [script], { cwd: workdir, timeoutMs: 180_000 });
  return { pass: res.code === 0, output: clip(`${res.stdout}${res.stderr}`.trim(), 4000) };
}

async function judge(c, summary, check, opts) {
  const prompt = `You are grading how a coding agent handled a task. Judge only from the evidence below; do not give credit for things the transcript does not show.

## Task given to the agent
${c.prompt}

## Criteria
${c.criteria}

## Automated check, run after the agent finished
${check ? `${check.pass ? 'PASSED' : 'FAILED'}\n${check.output}` : 'No automated check for this case.'}

## Transcript
${clip(summary.transcript, 60_000)}

Reply with only a JSON object, no prose and no code fence, with one entry per criterion in order:
{"criteria": [{"criterion": "<short name>", "pass": true, "reason": "<one sentence citing the transcript>"}]}`;
  const res = await run(
    'claude',
    ['-p', '--output-format', 'json', '--max-turns', '1', '--tools', '', '--model', opts.judgeModel, ...ISOLATION],
    { cwd: os.tmpdir(), input: prompt, timeoutMs: 300_000 },
  );
  try {
    const out = JSON.parse(res.stdout);
    const verdict = JSON.parse(out.result.match(/\{[\s\S]*\}/)[0]);
    const passed = verdict.criteria.filter((v) => v.pass).length;
    return { criteria: verdict.criteria, score: passed / verdict.criteria.length, costUsd: out.total_cost_usd ?? 0 };
  } catch {
    const detail = clip(res.stdout || res.stderr, 1000) || `no output (exit ${res.code}${res.timedOut ? ', timed out' : ''})`;
    return { error: detail, score: null, costUsd: 0 };
  }
}

async function runOne(c, arm, n, opts, outDir) {
  const label = `${c.id} [${arm} #${n}]`;
  const workdir = await prepareWorkdir(c);
  const agent = await runAgent(c, arm, workdir, opts);
  const summary = summarise(agent.events, c.skill);

  const record = { case: c.id, expect: c.expect, arm, n, workdir: opts.keep ? workdir : undefined };
  const problems = [];
  if (agent.timedOut) problems.push('timed out');
  if (arm === 'with' && !summary.plugins.includes('tackle')) {
    problems.push(`Tackle did not load: ${JSON.stringify(summary.pluginErrors)}`);
  }
  const foreign = summary.plugins.filter((p) => p !== 'tackle');
  if (foreign.length) problems.push(`other plugins loaded: ${foreign.join(', ')}`);
  if (!agent.events.some((e) => e.type === 'result')) problems.push(`no result: ${clip(agent.stderr, 500)}`);
  const brokenShell = summary.transcript.match(/\b(ls|cat|find|grep|which|sed): command not found/);
  if (brokenShell) problems.push(`shell PATH broken in run (${brokenShell[0]})`);

  const check = await runCheck(c, workdir);
  const verdict = problems.length ? { score: null, costUsd: 0 } : await judge(c, summary, check, opts);

  const base = path.join(outDir, c.id.replace('/', '__'), `${arm}-${n}`);
  fs.mkdirSync(path.dirname(base), { recursive: true });
  fs.writeFileSync(`${base}.jsonl`, agent.stdout);
  fs.writeFileSync(`${base}.txt`, summary.transcript);
  if (!opts.keep) fs.rmSync(workdir, { recursive: true, force: true });

  const { transcript, ...metrics } = summary;
  Object.assign(record, metrics, { check, verdict, problems });
  const fired = arm === 'with' ? ` fired=${summary.fired}` : '';
  const score = verdict.score == null ? 'n/a' : verdict.score.toFixed(2);
  console.log(
    `${label}${fired} check=${check ? check.pass : 'n/a'} rubric=${score} turns=${summary.turns}` +
      `${summary.delegated ? `(+${summary.subagentsSpawned} subagent)` : ''} ` +
      `$${summary.costUsd.toFixed(3)}${problems.length ? `  PROBLEM: ${problems.join('; ')}` : ''}`,
  );
  return record;
}

async function pool(tasks, size) {
  const results = [];
  let next = 0;
  const worker = async () => {
    while (next < tasks.length) {
      const i = next++;
      results[i] = await tasks[i]();
    }
  };
  await Promise.all(Array.from({ length: Math.max(1, size) }, worker));
  return results;
}

const mean = (xs) => (xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : NaN);
const fmt = (x, digits = 0) => (Number.isNaN(x) ? '—' : x.toFixed(digits));
const rate = (xs) => (xs.length ? `${xs.filter(Boolean).length}/${xs.length}` : '—');

function report(cases, records, opts) {
  const rows = [
    '| Case | Expect | Arm | Skill fired | Check passed | Rubric | Turns | Delegated | Output tokens | Cost (USD) |',
    '|---|---|---|---|---|---|---|---|---|---|',
  ];
  for (const c of cases) {
    for (const arm of opts.arms) {
      const rs = records.filter((r) => r.case === c.id && r.arm === arm && !r.problems.length);
      rows.push(
        `| ${c.id} | ${c.expect} | ${arm} | ${arm === 'with' ? rate(rs.map((r) => r.fired)) : '—'} ` +
          `| ${rate(rs.filter((r) => r.check).map((r) => r.check.pass))} ` +
          `| ${fmt(mean(rs.filter((r) => r.verdict.score != null).map((r) => r.verdict.score)), 2)} ` +
          `| ${fmt(mean(rs.map((r) => r.turns)), 1)} | ${rate(rs.map((r) => r.delegated))} ` +
          `| ${fmt(mean(rs.map((r) => r.outputTokens)))} ` +
          `| ${fmt(mean(rs.map((r) => r.costUsd)), 3)} |`,
      );
    }
  }
  const details = records.map((r) => {
    const head = `### ${r.case} [${r.arm} #${r.n}]`;
    if (r.problems.length) return `${head}\n\nExcluded: ${r.problems.join('; ')}`;
    const crit = (r.verdict.criteria ?? [])
      .map((v) => `- ${v.pass ? 'PASS' : 'FAIL'} ${v.criterion}: ${v.reason}`)
      .join('\n');
    return [
      head,
      `Skills used: ${r.skillsUsed.join(', ') || 'none'}`,
      r.delegated ? `Delegated to ${r.subagentsSpawned} subagent(s); turns count the main thread only.` : '',
      r.check ? `Check: ${r.check.pass ? 'passed' : 'failed'}\n\n\`\`\`\n${r.check.output}\n\`\`\`` : '',
      crit || `Judge error: ${r.verdict.error}`,
    ]
      .filter(Boolean)
      .join('\n\n');
  });
  const excluded = records.filter((r) => r.problems.length).length;
  const cost = records.reduce((a, r) => a + r.costUsd + r.verdict.costUsd, 0);
  return [
    '# Eval results',
    rows.join('\n'),
    `Averages are per run. ${excluded} run(s) excluded. Total cost including judging: $${cost.toFixed(2)}.`,
    '## Runs',
    ...details,
  ].join('\n\n');
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  const cases = loadCases(opts.case);
  if (!cases.length) {
    console.error('No cases matched.');
    process.exit(2);
  }
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  const outDir = path.join(evalsDir, 'results', stamp);
  fs.mkdirSync(outDir, { recursive: true });

  const tasks = [];
  for (const c of cases) {
    for (let n = 1; n <= (opts.runs ?? c.runs ?? 3); n++) {
      for (const arm of opts.arms) tasks.push(() => runOne(c, arm, n, opts, outDir));
    }
  }
  console.log(`Running ${tasks.length} runs across ${cases.length} case(s); results in ${outDir}`);
  const records = await pool(tasks, opts.concurrency);

  fs.writeFileSync(path.join(outDir, 'runs.json'), JSON.stringify(records, null, 2));
  const summary = report(cases, records, opts);
  fs.writeFileSync(path.join(outDir, 'summary.md'), summary);
  console.log(`\n${summary.split('\n## Runs')[0]}\nDetails: ${path.join(outDir, 'summary.md')}`);
}

main();
