import 'dotenv/config';
import { buildApp } from './createApp.js';
import { createBackend } from './backend/index.js';
import { loadConfig } from './config.js';

const config = loadConfig();
const backend = await createBackend(config);
const app = await buildApp({ config, backend });

if (config.DATA_BACKEND === 'memory') app.log.warn('DATA_BACKEND=memory: data is kept in RAM only (development)');

const shutdown = async (sig: string) => {
  app.log.info({ sig }, 'shutting down');
  await app.close();
  process.exit(0);
};
process.on('SIGINT', () => void shutdown('SIGINT'));
process.on('SIGTERM', () => void shutdown('SIGTERM'));

await app.listen({ port: config.PORT, host: config.HOST });
