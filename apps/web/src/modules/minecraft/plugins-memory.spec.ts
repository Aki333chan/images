import type { MinecraftPluginsDto } from '@aurum/shared';
import { hasEnabledPlugin, rememberedFits, type Remembered } from './PluginsPanel';
import { MODULE_REGISTRY } from '../registry';

const DATA = { available: true, installed: [], known: [] } as unknown as MinecraftPluginsDto;
const kept = (bootAt: number | null): Remembered => ({ data: DATA, bootAt });

/**
 * Память списка плагинов действует до следующего ЗАПУСКА сервера.
 *
 * Проверять приходится тестом, потому что обе ошибки выглядят одинаково
 * правдоподобно на экране: и вечно показанный вчерашний список, и сплошное
 * «нет» при живом сервере — это просто таблица, и понять, какая из них перед
 * тобой, по виду нельзя.
 */
describe('память списка плагинов', () => {
  it('сервер выключен — показываем запомненное', () => {
    // Иначе таблица превращается в сплошное «нет» ровно тогда, когда
    // спросить некого, и выглядит это как «плагины пропали».
    expect(rememberedFits(kept(1_000), null)).toBe(true);
  });

  it('тот же запуск — запомненное ещё в силе', () => {
    expect(rememberedFits(kept(1_700_000_000_000), 1_700_000_000_000)).toBe(true);
  });

  it('сервер перезапустили — запомненное больше не годится', () => {
    // После перезапуска набор плагинов мог стать другим, и выдавать
    // вчерашний список за сегодняшний нельзя.
    expect(rememberedFits(kept(1_700_000_000_000), 1_700_000_600_000)).toBe(false);
  });

  it('запуск, при котором запоминали, неизвестен — к работающему серверу не подходит', () => {
    // Данные без привязки к запуску нельзя засчитать работающему серверу:
    // неизвестно, тот ли это запуск. Привязку дописывает отдельный эффект,
    // как только Pterodactyl отвечает.
    expect(rememberedFits(kept(null), 1_700_000_000_000)).toBe(false);
  });
});

describe('зависимые от плагинов вкладки', () => {
  const plugins = (available: boolean, installed: boolean): MinecraftPluginsDto => ({
    available,
    installed: installed ? [{ name: 'AurumCore', version: '0.15.0', enabled: true }] : [],
    known: [{
      id: 'AurumCore', displayName: 'AurumCore', givesKey: 'mc.plugin.gives.aurumcore',
      installed, version: installed ? '0.15.0' : null,
    }],
  });

  it('экономика объявлена модулем как вкладка, зависящая от AurumCore', () => {
    expect(MODULE_REGISTRY.minecraft!.tabs.economy?.requiresPlugin).toBe('AurumCore');
  });

  it('вкладка появляется только по живому включённому AurumCore', () => {
    expect(hasEnabledPlugin(null, 'AurumCore')).toBe(false);
    expect(hasEnabledPlugin(plugins(false, false), 'AurumCore')).toBe(false);
    expect(hasEnabledPlugin(plugins(true, false), 'AurumCore')).toBe(false);
    expect(hasEnabledPlugin(plugins(true, true), 'aurumcore')).toBe(true);
  });
});
