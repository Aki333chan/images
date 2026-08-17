process.env.NODE_ENV = 'test';

import { ValidationPipe } from '@nestjs/common';
import { SmtpPatchDto } from './settings.controller';

/**
 * Валидация тела PUT /settings/smtp.
 *
 * Тесты писались по следам реальной поломки: экран настроек отправлял весь
 * объект, полученный из GET, вместе с полями configured и hasPassword, а
 * глобальный ValidationPipe стоит с forbidNonWhitelisted и отвергал запрос
 * целиком — «property configured should not exist». Настроить почту было
 * невозможно при любых введённых данных.
 */
describe('SmtpPatchDto', () => {
  // Тот же пайп, что и в main.ts: проверяем ровно то поведение, что в бою.
  const pipe = new ValidationPipe({
    whitelist: true,
    transform: true,
    forbidNonWhitelisted: true,
  });
  const meta = { type: 'body' as const, metatype: SmtpPatchDto };

  /** То, что реально отправляет форма настроек. */
  const valid = {
    host: 'mail.aurumgg.ovh',
    port: 465,
    secure: true,
    user: 'panel@aurumgg.ovh',
    password: 'пароль-ящика',
    from: 'Aurum Panel <panel@aurumgg.ovh>',
  };

  it('принимает то, что отправляет экран настроек', async () => {
    await expect(pipe.transform({ ...valid }, meta)).resolves.toMatchObject(valid);
  });

  // Главная регрессия: адрес с именем отправителя — это то, что советуют и
  // подсказка в поле, и инструкция по развёртыванию. Голый @IsEmail() такое
  // значение не пропускает.
  it('принимает адрес отправителя с именем', async () => {
    await expect(
      pipe.transform({ ...valid, from: 'Aurum Panel <panel@aurumgg.ovh>' }, meta),
    ).resolves.toBeDefined();
  });

  it('принимает и голый адрес отправителя', async () => {
    await expect(
      pipe.transform({ ...valid, from: 'panel@aurumgg.ovh' }, meta),
    ).resolves.toBeDefined();
  });

  it('отвергает мусор вместо адреса отправителя', async () => {
    await expect(pipe.transform({ ...valid, from: 'не адрес' }, meta)).rejects.toThrow();
  });

  it('пароль необязателен — пустое поле означает «оставить прежний»', async () => {
    const { password: _password, ...withoutPassword } = valid;
    await expect(pipe.transform(withoutPassword, meta)).resolves.toBeDefined();
  });

  // Ради этого тест и написан: поля из ответа GET не должны попадать в PUT.
  // Если кто-то снова отправит весь объект настроек, тест назовёт причину.
  it('поля только для чтения из ответа GET запрещены', async () => {
    // Сообщения лежат в теле ответа, а не в message самого исключения:
    // у BadRequestException он всегда «Bad Request Exception».
    const error = await pipe
      .transform({ ...valid, configured: true, hasPassword: true }, meta)
      .then(() => null)
      .catch((e: { getResponse(): { message?: string[] } }) => e);
    expect(error).not.toBeNull();
    const messages = error!.getResponse().message ?? [];
    expect(messages.join(' ')).toContain('configured should not exist');
    expect(messages.join(' ')).toContain('hasPassword should not exist');
  });

  it('порт вне диапазона отклоняется', async () => {
    await expect(pipe.transform({ ...valid, port: 0 }, meta)).rejects.toThrow();
    await expect(pipe.transform({ ...valid, port: 70000 }, meta)).rejects.toThrow();
  });

  // Из <select> значение приходит строкой, и приводить его к числу должен
  // фронтенд: transform:true сам по себе строку в число не превращает —
  // для этого нужна enableImplicitConversion, которую мы не включаем.
  // Тест фиксирует эту границу ответственности.
  it('порт строкой отвергается — приводить к числу обязан фронтенд', async () => {
    await expect(pipe.transform({ ...valid, port: '465' }, meta)).rejects.toThrow();
  });
});
