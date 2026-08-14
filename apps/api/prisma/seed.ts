/**
 * Bootstrap: создаёт владельца (роль OWNER / «ГМ») из OWNER_EMAIL/OWNER_PASSWORD,
 * если в БД ещё нет ни одного пользователя.
 */
import { config as loadDotenv } from 'dotenv';
import { PrismaClient } from '@prisma/client';
import * as argon2 from 'argon2';

loadDotenv();

const prisma = new PrismaClient();

async function main() {
  const count = await prisma.user.count();
  if (count > 0) {
    console.log('Пользователи уже существуют, сид пропущен.');
    return;
  }
  const email = process.env.OWNER_EMAIL;
  const password = process.env.OWNER_PASSWORD;
  if (!email || !password) {
    throw new Error('OWNER_EMAIL и OWNER_PASSWORD должны быть заданы в .env для первого сида');
  }
  await prisma.user.create({
    data: {
      email: email.toLowerCase(),
      passwordHash: await argon2.hash(password),
      displayName: process.env.OWNER_NAME ?? 'ГМ',
      role: 'OWNER',
    },
  });
  console.log(`Создан владелец ${email}`);
}

main()
  .catch((e) => {
    console.error(e);
    process.exit(1);
  })
  .finally(() => prisma.$disconnect());
