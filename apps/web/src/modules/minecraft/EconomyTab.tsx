import { useAuth } from '../../lib/auth';
import type { ModuleTabProps } from '../registry';
import { EconomyAuditPanel } from './EconomyAuditPanel';
import { EconomyOverviewPanel } from './EconomyOverviewPanel';
import { EconomyRulesEditor } from './EconomyRulesEditor';

/**
 * Нативная экономика Minecraft — вкладка игрового модуля, а не карточка
 * общего ServerDetail. Само наличие вкладки уже проверено по AurumCore;
 * изменение правил дополнительно закрыто более сильным правом администратора.
 */
export function MinecraftEconomyTab({ serverId }: ModuleTabProps) {
  const { hasPermission } = useAuth();

  return (
    <div className="space-y-3">
      <EconomyOverviewPanel serverId={serverId} />
      <EconomyAuditPanel serverId={serverId} />
      {hasPermission('minecraft.economy.admin') ? (
        <EconomyRulesEditor serverId={serverId} />
      ) : null}
    </div>
  );
}
