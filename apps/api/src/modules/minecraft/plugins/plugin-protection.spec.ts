process.env.NODE_ENV = 'test';

import { KNOWN_PLUGINS } from '@aurum/shared';
import { COMPANION_PLUGIN, fileProtectionReason, protectionReason } from './plugin-protection';

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
