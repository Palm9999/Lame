import { test } from 'node:test';
import assert from 'node:assert/strict';
import { postPath } from '../src/title.js';

test('builds a post path from a title', () => {
  assert.equal(postPath('Hello, World!'), '/posts/hello-world');
});
