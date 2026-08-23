process.env.NODE_ENV = 'test';

import { BadRequestException } from '@nestjs/common';
import {
  baseName,
  breadcrumbsFor,
  joinPath,
  normalizeName,
  normalizePath,
  parentOf,
} from './file-paths';

/**
 * Пути файлового менеджера.
 *
 * Путь приходит из браузера, а уходит в Wings, который работает с настоящей
 * файловой системой. Это единственное место в панели, где строка из формы
 * превращается в обращение к диску, — поэтому проверок здесь больше, чем
 * кажется нужным.
 */
describe('normalizePath', () => {
  it('приводит путь к каноническому виду', () => {
    expect(normalizePath('/plugins/config.yml')).toBe('/plugins/config.yml');
    expect(normalizePath('plugins/config.yml')).toBe('/plugins/config.yml');
    expect(normalizePath('/plugins/')).toBe('/plugins');
    expect(normalizePath('//plugins///sub//')).toBe('/plugins/sub');
    expect(normalizePath('/plugins/./sub')).toBe('/plugins/sub');
  });

  it('пустой путь — это корень', () => {
    expect(normalizePath('')).toBe('/');
    expect(normalizePath('   ')).toBe('/');
    expect(normalizePath(undefined)).toBe('/');
    expect(normalizePath('/')).toBe('/');
  });

  /**
   * Подниматься на уровень выше не разрешаем ВООБЩЕ, а не «схлопываем».
   * Схлопывание молча превратило бы «/../../etc/passwd» в «/etc/passwd» —
   * путь валидный с виду, но означающий совсем не то, что просили.
   */
  it('переход вверх по дереву отвергается, а не схлопывается', () => {
    for (const evil of ['/../etc/passwd', '/plugins/../../etc', '..', '/a/b/../c']) {
      expect(() => normalizePath(evil)).toThrow(BadRequestException);
    }
  });

  // «файл.txt\0.jpg» прошёл бы проверку расширения и открыл бы другой файл.
  it('нулевой байт отвергается', () => {
    expect(() => normalizePath('/plugins/config\0.yml')).toThrow(BadRequestException);
  });

  it('слишком длинный путь отвергается', () => {
    expect(() => normalizePath('/' + 'a'.repeat(2000))).toThrow(BadRequestException);
  });

  it('кириллица и пробелы в имени допустимы', () => {
    expect(normalizePath('/мир/данные мира.dat')).toBe('/мир/данные мира.dat');
  });
});

describe('normalizeName', () => {
  it('обычное имя проходит', () => {
    expect(normalizeName(' config.yml ')).toBe('config.yml');
    expect(normalizeName('мир')).toBe('мир');
  });

  it('слэш в имени отвергается: это уже путь, а не имя', () => {
    expect(() => normalizeName('a/b')).toThrow(BadRequestException);
    expect(() => normalizeName('../etc')).toThrow(BadRequestException);
  });

  it('точки как имя отвергаются', () => {
    expect(() => normalizeName('.')).toThrow(BadRequestException);
    expect(() => normalizeName('..')).toThrow(BadRequestException);
  });

  it('пустое имя отвергается', () => {
    expect(() => normalizeName('')).toThrow(BadRequestException);
    expect(() => normalizeName('   ')).toThrow(BadRequestException);
  });
});

describe('parentOf и baseName', () => {
  it('разбирают путь на каталог и имя', () => {
    expect(parentOf('/plugins/config.yml')).toBe('/plugins');
    expect(baseName('/plugins/config.yml')).toBe('config.yml');
  });

  it('в корне родитель — тоже корень', () => {
    expect(parentOf('/server.properties')).toBe('/');
    expect(parentOf('/')).toBe('/');
    expect(baseName('/')).toBe('');
  });
});

describe('joinPath', () => {
  it('склеивает без двойных слэшей', () => {
    expect(joinPath('/', 'server.properties')).toBe('/server.properties');
    expect(joinPath('/plugins', 'config.yml')).toBe('/plugins/config.yml');
    expect(joinPath('/plugins/', 'config.yml')).toBe('/plugins/config.yml');
  });
});

describe('breadcrumbsFor', () => {
  it('в корне — одна крошка', () => {
    expect(breadcrumbsFor('/')).toEqual([{ name: 'Корень', path: '/' }]);
  });

  it('накапливает путь по сегментам', () => {
    expect(breadcrumbsFor('/plugins/Essentials')).toEqual([
      { name: 'Корень', path: '/' },
      { name: 'plugins', path: '/plugins' },
      { name: 'Essentials', path: '/plugins/Essentials' },
    ]);
  });
});
