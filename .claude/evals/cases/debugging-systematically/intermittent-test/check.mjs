// Passes only if the queue itself waits for its jobs. A longer sleep in the test would pass
// the repeated runs but fail the slow-worker check, because the race is still there.
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

let failed = false;
const report = (ok, name, detail = '') => {
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? `: ${detail}` : ''}`);
  if (!ok) failed = true;
};

let passes = 0;
for (let i = 0; i < 20; i++) {
  if (spawnSync('node', ['--test'], { encoding: 'utf8' }).status === 0) passes++;
}
report(passes === 20, 'suite passes on 20 consecutive runs', `${passes}/20`);

try {
  const { JobQueue } = await import(pathToFileURL(path.resolve('src/queue.js')));
  const slow = async (name) => {
    await new Promise((resolve) => setTimeout(resolve, 150));
    return `${name}.thumb.png`;
  };
  const queue = new JobQueue(slow);
  // A correct fix may resolve to the results or just collect them on the instance; either is fine,
  // as long as awaiting processAll means every job has finished.
  const returned = await queue.processAll(['a', 'b', 'c']);
  const fromReturn = Array.isArray(returned) ? returned.filter((v) => v != null) : [];
  const got = fromReturn.length === 3 ? fromReturn : queue.results;
  const ok = JSON.stringify([...got].sort()) === JSON.stringify(['a.thumb.png', 'b.thumb.png', 'c.thumb.png']);
  report(ok, 'processAll can be awaited until a 150ms worker finishes', JSON.stringify(got));
} catch (err) {
  report(false, 'processAll can be awaited until a 150ms worker finishes', err.message);
}

process.exit(failed ? 1 : 0);
