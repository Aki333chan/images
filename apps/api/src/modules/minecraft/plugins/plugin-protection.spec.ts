process.env.NODE_ENV = 'test';

import { KNOWN_PLUGINS, type InstalledPluginDto } from '@aurum/shared';
import { COMPANION_PLUGIN, fileProtectionReason, protectionReason } from './plugin-protection';
import { byProtectedThenName } from './plugin-files.service';

/**
 * Плагины, на которых панель стоит, из панели же не выключаются.
 *
 * Проверять это тестами приходится потому, что цена ошибки несимметрична.
 * Лишний запрет — это «уберите файл руками по SFTP», неудобно и не более.
 * Пропущенный запрет на LuckPerms — это сервер, у которого после ближайшего
 * перезапуска не осталось прав ни у кого, и панель, которой уже нечем это
 * исправить.
 */
describe('защита плагинов панели', () => {
  it('companion выключить нельзя', () => {
    expect(protectionReason(COMPANION_PLUGIN)).toContain('потеряет связь');
  });

  it('каждый из KNOWN_PLUGINS защищён', () => {
    // Список берётся из самого контракта: добавленный туда плагин попадает
    // под защиту автоматически, и забыть про него здесь невозможно.
    for (const plugin of KNOWN_PLUGINS) {
      expect(protectionReason(plugin.id)).not.toBeNull();
    }
  });

  it('обычный плагин не трогаем', () => {
    expect(protectionReason('WorldEdit')).toBeNull();
    expect(protectionReason('CoreProtect')).toBeNull();
  });

  it('регистр и знаки в имени не спасают', () => {
    expect(protectionReason('luckperms')).not.toBeNull();
    expect(protectionReason('InvSee++')).not.toBeNull();
  });

  it('соседний плагин с похожим именем не защищён', () => {
    // Имя из Bukkit точное, поэтому и сравнение точное: EssentialsChat —
    // отдельный плагин, и запрещать его заодно оснований нет.
    expect(protectionReason('EssentialsChat')).toBeNull();
  });

  describe('по имени файла', () => {
    it('версия в имени не мешает', () => {
      expect(fileProtectionReason('LuckPerms-Bukkit-5.4.144.jar')).not.toBeNull();
      expect(fileProtectionReason('EssentialsX-2.21.0.jar')).not.toBeNull();
      expect(fileProtectionReason('Vault.jar')).not.toBeNull();
      expect(fileProtectionReason('InvSeePlusPlus-0.30.1.jar')).not.toBeNull();
    });

    it('чужой файл переносить и удалять можно', () => {
      expect(fileProtectionReason('worldedit-bukkit-7.3.0.jar')).toBeNull();
      expect(fileProtectionReason('CoreProtect-22.4.jar')).toBeNull();
    });

    it('по файлу сравнение нарочно нестрогое', () => {
      // Тут точного имени из Bukkit нет — только то, как кто-то назвал файл.
      // Перестраховаться дешевле: вернуть файл из .disabled/ панель даёт, а
      // вот унести туда LuckPerms по недосмотру — уже происшествие.
      expect(fileProtectionReason('EssentialsXSpawn-2.21.0.jar')).not.toBeNull();
    });
  });
});

/**
 * Порядок установленных плагинов в настройках.
 *
 * Неотключаемые сверху, и это не вкусовщина: у них нет ни тумблера, ни кнопки
 * удаления — они выглядят иначе, чем остальные строки. Вперемешку такой
 * список читается как «у части плагинов почему-то пропали кнопки»; собранные
 * в одну группу они читаются как то, чем являются, — как основа панели.
 */
describe('сортировка установленных плагинов', () => {
  const plugin = (name: string, isProtected: boolean): InstalledPluginDto => ({
    name,
    version: null,
    state: 'enabled',
    fileName: `${name}.jar`,
    protected: isProtected,
  });

  it('неотключаемые идут первыми', () => {
    const sorted = [
      plugin('Zebra', false),
      plugin('LuckPerms', true),
      plugin('Alpha', false),
      plugin('Vault', true),
    ].sort(byProtectedThenName);

    expect(sorted.map((p) => p.name)).toEqual(['LuckPerms', 'Vault', 'Alpha', 'Zebra']);
  });

  it('внутри группы — по алфавиту, без учёта регистра', () => {
    const sorted = [plugin('bravo', false), plugin('Alpha', false), plugin('charlie', false)].sort(
      byProtectedThenName,
    );
    expect(sorted.map((p) => p.name)).toEqual(['Alpha', 'bravo', 'charlie']);
  });

  it('кириллические имена не уезжают в конец', () => {
    // localeCompare с русской локалью, а не побайтовое сравнение: иначе любой
    // плагин с русским названием оказался бы ниже всех латинских.
    const sorted = [plugin('Яндекс', false), plugin('Альфа', false)].sort(byProtectedThenName);
    expect(sorted.map((p) => p.name)).toEqual(['Альфа', 'Яндекс']);
  });
});
