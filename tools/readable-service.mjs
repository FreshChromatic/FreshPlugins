import { readFileSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { parsers } from 'prettier/plugins/babel';

const check = process.argv.includes('--check');
const paths = execFileSync(
  'rg',
  ['--files', 'services', '-g', '*.mjs', '-g', '!**/node_modules/**', '-g', '!**/build/**'],
  {
    encoding: 'utf8',
  },
)
  .trim()
  .split(/\r?\n/);
let changes = false;

for (const path of paths) {
  const source = readFileSync(path, 'utf8');
  const ast = parsers.babel.parse(source);
  const insertions = [];

  function wrap(body) {
    if (!body || body.type === 'BlockStatement') {
      return;
    }
    insertions.push({ offset: body.start, text: '{\n' });
    insertions.push({ offset: body.end, text: '\n}' });
  }

  function visit(node) {
    if (!node || typeof node !== 'object') {
      return;
    }
    if (node.type === 'IfStatement') {
      wrap(node.consequent);
      if (node.alternate?.type !== 'IfStatement') {
        wrap(node.alternate);
      }
    }
    const loop = [
      'ForStatement',
      'ForInStatement',
      'ForOfStatement',
      'WhileStatement',
      'DoWhileStatement',
    ];
    if (loop.includes(node.type)) {
      wrap(node.body);
    }
    for (const value of Object.values(node)) {
      if (Array.isArray(value)) {
        value.forEach(visit);
      } else if (value?.type) {
        visit(value);
      }
    }
  }

  visit(ast);
  if (insertions.length === 0) {
    continue;
  }
  changes = true;
  if (check) {
    console.log(path);
    continue;
  }
  insertions.sort((first, second) => second.offset - first.offset);
  let result = source;
  for (const insertion of insertions) {
    result = result.slice(0, insertion.offset) + insertion.text + result.slice(insertion.offset);
  }
  writeFileSync(path, result);
}

if (check && changes) {
  process.exitCode = 1;
}
