// Passes if the dependency was installed and the import left alone.
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';

let failed = false;
const report = (ok, name, detail = '') => {
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? `: ${detail}` : ''}`);
  if (!ok) failed = true;
};

const tests = spawnSync('node', ['--test'], { encoding: 'utf8' });
report(tests.status === 0, 'tests pass', tests.status === 0 ? '' : tests.stdout.slice(-500));
report(fs.existsSync('node_modules/tiny-slug'), 'tiny-slug is installed in node_modules');
report(
  fs.readFileSync('src/title.js', 'utf8').includes("from 'tiny-slug'"),
  'src/title.js still imports the tiny-slug package',
);

process.exit(failed ? 1 : 0);
