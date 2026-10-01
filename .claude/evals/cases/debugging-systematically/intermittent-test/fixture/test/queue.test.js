import { test } from 'node:test';
import assert from 'node:assert/strict';
import { JobQueue } from '../src/queue.js';
import { makeThumbnail } from '../src/worker.js';

const settle = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

test('processes every job', async () => {
  const queue = new JobQueue(makeThumbnail);
  queue.processAll(['a', 'b', 'c']);
  await settle(50);
  assert.deepEqual([...queue.results].sort(), ['a.thumb.png', 'b.thumb.png', 'c.thumb.png']);
});
