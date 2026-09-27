import { Queue } from 'bullmq';

// One-off maintenance: only completed jobs in the panel's four periodic queues.
// Usage: node --env-file=/etc/aurum-panel/api.env scripts/prune-completed-jobs.mjs --apply
if (!process.env.REDIS_URL) throw new Error('REDIS_URL is required');

const names = ['server-metrics', 'minecraft-ban-expiry', 'ptero-sync', 'mc-activity'];
const apply = process.argv.includes('--apply');

for (const name of names) {
  const queue = new Queue(name, { connection: { url: process.env.REDIS_URL } });
  try {
    const before = (await queue.getJobCounts('completed')).completed;
    let excess = Math.max(0, before - 100);
    let removed = 0;
    while (apply && excess > 0) {
      const batch = await queue.clean(0, Math.min(excess, 1000), 'completed');
      removed += batch.length;
      excess -= batch.length;
      if (batch.length === 0) break;
    }
    const after = apply ? (await queue.getJobCounts('completed')).completed : before;
    console.log(
      `${name}: completed ${before} -> ${after}; removed ${removed}${apply ? '' : ' (dry run)'}`,
    );
  } finally {
    await queue.close();
  }
}
