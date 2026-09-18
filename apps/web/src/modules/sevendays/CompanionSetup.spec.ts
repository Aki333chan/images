import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { SEVENDAYS_COMPANION_CAPABILITIES } from '@aurum/shared';

// Match the repository's node-only web tests; browser behavior is checked separately.
describe('Companion setup identifiers and translations', () => {
  it('copies the full panel ID as a config line, never the Pterodactyl ID', () => {
    const source = readFileSync(join(__dirname, 'CompanionSetup.tsx'), 'utf8');
    expect(source).toContain('`server-id = ${serverId}`');
    expect(source).toContain('navigator.clipboard.writeText(line)');
    expect(source).not.toContain('pteroIdentifier');
    expect(source).toContain('readOnly');
    expect(source).toContain('catch');
    const settings = readFileSync(join(__dirname, 'SettingsTab.tsx'), 'utf8');
    expect(settings).toContain('<CompanionSetup serverId={serverId} />');
  });
  it.each(['en', 'ru', 'pl'])('provides setup instructions in %s', (locale) => {
    const catalog = JSON.parse(
      readFileSync(join(__dirname, '../../i18n/catalogs', `${locale}.json`), 'utf8'),
    );
    for (const name of ['id', 'idHint', 'networkHint', 'statusHint', 'copied', 'copyFailed']) {
      expect(catalog[`sdtd.setup.${name}`].length).toBeGreaterThan(4);
    }
    expect(catalog['sdtd.setup.idHint']).toContain('Pterodactyl');
    expect(catalog['sdtd.setup.networkHint']).toContain('8110');
    for (const capability of SEVENDAYS_COMPANION_CAPABILITIES) {
      expect(catalog[`sdtd.capability.${capability}`].length).toBeGreaterThan(2);
    }
    for (const name of ['incompatible', 'legacy', 'languageHint', 'savedOffline']) {
      expect(catalog[`sdtd.connection.${name}`].length).toBeGreaterThan(10);
    }
  });
});
