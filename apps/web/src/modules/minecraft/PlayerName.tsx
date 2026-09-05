import { useT } from '../../i18n';
/**
 * Имя игрока так, как оно показывается везде в панели.
 *
 * Три части, и каждая берётся из своего источника:
 *
 * - жёлтая звёздочка — оператор сервера (`isOp()`). Ванильные данные, есть
 *   на любом сервере и ни от каких плагинов не зависят;
 * - настоящее игровое имя — то, под которым игрок зашёл;
 * - алиас в скобках — ник из EssentialsX. Нет плагина или ник не задан —
 *   скобок нет вовсе.
 *
 * Отдельным компонентом, а не тремя копиями разметки: имя игрока рисуется в
 * таблице онлайна, в историческом списке и в карточке, и звёздочка, забытая
 * в одном из трёх мест, читалась бы как «здесь он не оператор».
 */
export function PlayerName({
  name,
  alias = null,
  op = false,
  className,
}: {
  name: string;
  alias?: string | null;
  op?: boolean;
  className?: string;
}) {
  const t = useT();
  return (
    <span className={className}>
      {op && (
        <span className="mr-1 text-amber-400" title={t('mc.op')} aria-label={t('mc.op.short')}>
          ★
        </span>
      )}
      <span className="font-medium">{name}</span>
      {alias && <span className="ml-1 text-xs text-muted">({alias})</span>}
    </span>
  );
}
