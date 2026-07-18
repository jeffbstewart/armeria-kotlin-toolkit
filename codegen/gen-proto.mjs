#!/usr/bin/env node
//
// TEMPLATE — copy into your web app (e.g. web-app/scripts/gen-proto.mjs)
// and adjust PROTO_FILES and the paths for your layout.
//
// Generates TypeScript types AND Connect service stubs from the
// project's .proto files. Single source of truth: <repo>/proto/*.proto.
//
// Stack: @bufbuild/protoc-gen-es (>= 2.x) emits both messages and
// Connect service definitions in one pass — no separate connect-es
// plugin needed. The runtime side uses @connectrpc/connect-web's
// createGrpcWebTransport, which talks application/grpc-web+proto to an
// ArmeriaAppServer directly (no proxy). Configure the transport with
// `credentials: 'include'` so HttpOnly auth cookies ride along.
//
// npm devDependencies: protoc, @bufbuild/protoc-gen-es
// npm runtime deps:    @bufbuild/protobuf, @connectrpc/connect,
//                      @connectrpc/connect-web
//
// Wire into package.json so builds can never use stale types:
//   "proto:gen": "node scripts/gen-proto.mjs",
//   "prebuild": "npm run proto:gen",
//   "prestart": "npm run proto:gen"
// and gitignore the output directory — generated code is never
// committed, so there is nothing to drift.

import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, rmSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const webRoot = resolve(here, '..');
// Conventional repo-root proto/ first, then /proto (Docker layout).
const protoRoot = [
  resolve(webRoot, '..', 'proto'),
  '/proto',
].find(p => existsSync(p));
const outDir = join(webRoot, 'src', 'app', 'proto-gen');

if (!protoRoot) {
  console.warn('proto codegen skipped: no proto/ sources found');
  process.exit(0);
}

// The .proto files your web app consumes, dependency-first.
const PROTO_FILES = ['common.proto'];

const protocBin = process.platform === 'win32'
  ? join(webRoot, 'node_modules', '.bin', 'protoc.cmd')
  : join(webRoot, 'node_modules', '.bin', 'protoc');
const esPlugin = process.platform === 'win32'
  ? join(webRoot, 'node_modules', '.bin', 'protoc-gen-es.cmd')
  : join(webRoot, 'node_modules', '.bin', 'protoc-gen-es');

rmSync(outDir, { recursive: true, force: true });
mkdirSync(outDir, { recursive: true });

const esOpts = [
  'target=ts',      // emit TypeScript, not JS
  'json_types=true' // Connect-compatible service definitions
];

const args = [
  `--plugin=protoc-gen-es=${esPlugin}`,
  `--es_out=${outDir}`,
  `--es_opt=${esOpts.join(',')}`,
  `--proto_path=${protoRoot}`,
  ...PROTO_FILES.map(f => join(protoRoot, f)),
];

console.log(`> protoc ${PROTO_FILES.join(' ')} → ${outDir}`);
execFileSync(protocBin, args, { stdio: 'inherit', shell: process.platform === 'win32' });
console.log('proto codegen complete.');
