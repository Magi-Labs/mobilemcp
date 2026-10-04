import * as esbuild from 'esbuild';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const shared = { bundle: true, format: 'esm', platform: 'node', target: 'node18', sourcemap: true, external: ['@modelcontextprotocol/sdk', '@modelcontextprotocol/sdk/*', 'zod', 'ws'] };
// MCP session process (one per agent session)
await esbuild.build({ ...shared, entryPoints: [join(root, 'src/index.ts')], outfile: join(root, 'dist/index.js'), banner: { js: '#!/usr/bin/env node' } });
// Hub daemon (one per machine or hosted, always-on)
await esbuild.build({ ...shared, entryPoints: [join(root, 'src/hub.ts')], outfile: join(root, 'dist/hub.js'), banner: { js: '#!/usr/bin/env node' } });
