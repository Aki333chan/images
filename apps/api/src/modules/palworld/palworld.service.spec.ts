process.env.NODE_ENV = 'test';

import { BadRequestException } from '@nestjs/common';
import { MODULE_CAPABILITIES, PALWORLD_PERMISSIONS, listCapabilities } from '@aurum/shared';
import { PALWORLD_ACTIONS } from './palworld-actions.config';
import { palworldManifest } from './palworld.def';
import { buildActionBody } from './palworld.service';

const action = (id: string) => PALWORLD_ACTIONS.find((a) => a.id === id)!;

describe('buildActionBody', () => {
  it('объявление уходит с текстом', () => {
    expect(buildActionBody(action('announce'), { message: 'Рестарт через 5 минут' })).toEqual({
      message: 'Рестарт через 5 минут',
    });
  });

  it('переводы строк в объявлении схлопываются', () => {
    expect(buildActionBody(action('announce'), { message: 'первая\nвторая' })).toEqual({
      message: 'первая вторая',
    });
  });

  it('обязательный аргумент требуется', () => {
    expect(() => buildActionBody(action('announce'), {})).toThrow(BadRequestException);
    expect(() => buildActionBody(action('announce'), { message: '   ' })).toThrow(
      BadRequestException,
    );
  });

  // Действие без аргументов шлём вовсе без тела: пустой объект часть версий
  // сервера воспринимает хуже, чем его отсутствие.
  it('сохранение мира уходит без тела', () => {
    expect(buildActionBody(action('save'), {})).toBeUndefined();
  });

  it('в тело попадают только объявленные аргументы', () => {
    // Лишнее из запроса клиента не должно уезжать на игровой сервер.
    expect(
      buildActionBody(action('announce'), { message: 'Привет', userid: 'steam_1', admin: 'true' }),
    ).toEqual({ message: 'Привет' });
  });

  it('секунды до остановки приходят числом, а не строкой', () => {
    // Сервер ждёт именно число; строка «60» молча ничего не сделает.
    const body = buildActionBody(action('shutdown'), { waittime: '60', message: 'Обновление' });
    expect(body).toEqual({ waittime: 60, message: 'Обновление' });
    expect(typeof body!.waittime).toBe('number');
  });

  it('необязательное сообщение можно не заполнять', () => {
    expect(buildActionBody(action('shutdown'), { waittime: '0' })).toEqual({ waittime: 0 });
  });

  it('нечисловые и запредельные секунды отклоняются', () => {
    for (const waittime of ['скоро', '-5', '1.5', '999999']) {
      expect(() => buildActionBody(action('shutdown'), { waittime })).toThrow(BadRequestException);
    }
  });
});

describe('манифест Palworld', () => {
  it('объявляет только то, что REST API действительно умеет', () => {
    const declared = listCapabilities(palworldManifest).map((c) => c.capability);
    expect(declared.sort()).toEqual(['banKick', 'console', 'playerList', 'quickCommands']);
  });

  // Регрессия на соблазн «объявить на всякий случай»: этих возможностей у
  // Palworld нет вовсе, и вкладка-заглушка хуже её отсутствия.
  it('не объявляет то, чего у игры нет', () => {
    for (const capability of ['whitelist', 'inventory', 'tickets'] as const) {
      expect({ capability, declared: capability in palworldManifest.capabilities }).toEqual({
        capability,
        declared: false,
      });
    }
  });

  it('все объявленные возможности известны ядру', () => {
    for (const { capability } of listCapabilities(palworldManifest)) {
      expect(MODULE_CAPABILITIES).toContain(capability);
    }
  });

  it('ключи прав начинаются с id модуля — конвенция ядра', () => {
    for (const permission of palworldManifest.permissions) {
      expect(permission.key.startsWith(`${palworldManifest.id}.`)).toBe(true);
    }
  });

  it('настройка подключения доступна только ГМ', () => {
    // Здесь задаётся пароль администратора игрового сервера.
    const configure = palworldManifest.permissions.find(
      (p) => p.key === PALWORLD_PERMISSIONS.configure,
    );
    expect(configure?.defaultRoles).toEqual([]);
  });

  it('остановка сервера модератору не выдаётся', () => {
    const shutdown = palworldManifest.permissions.find(
      (p) => p.key === PALWORLD_PERMISSIONS.shutdown,
    );
    expect(shutdown?.defaultRoles).toEqual(['ADMIN']);
  });

  it('каждое действие объявляет право, которое есть в манифесте', () => {
    const keys = new Set(palworldManifest.permissions.map((p) => p.key));
    for (const a of PALWORLD_ACTIONS) {
      expect({ id: a.id, known: keys.has(a.permission) }).toEqual({ id: a.id, known: true });
    }
  });

  it('идентификаторы действий уникальны', () => {
    const ids = PALWORLD_ACTIONS.map((a) => a.id);
    expect(new Set(ids).size).toBe(ids.length);
  });
});
