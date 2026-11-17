// SPDX-License-Identifier: AGPL-3.0-only
// gjs -m trust.test.js: the extension's access check, without a shell.
import {allowed, flatpakAppId} from './seam-clipboard@avetero.com/trust.js';

let failed = 0;
const check = (name, cond) => {
    if (!cond) {
        failed++;
        print(`FAIL ${name}`);
    }
};
const info = id => `[Application]\nname=${id}\nruntime=runtime/org.gnome.Platform/aarch64/51\n\n[Instance]\ninstance-id=1\n`;

check('reads the app id', flatpakAppId(info('com.avetero.Seam')) === 'com.avetero.Seam');
check('no info, no id', flatpakAppId(null) === null);
check('garbage, no id', flatpakAppId('not an ini [[') === null);
check('Seam Flatpak is allowed', allowed(info('com.avetero.Seam'), '/app/bin/seam', false));
check('another Flatpak app is refused', !allowed(info('org.example.Spy'), '/app/bin/seam', false));
check('a lookalike id is refused', !allowed(info('com.avetero.Seam.Evil'), '/app/bin/seam', false));
check('a host process is refused', !allowed(null, '/usr/bin/python3', false));
check('a host "seam" is refused without the override', !allowed(null, '/home/u/seam', false));
check('a host "seam" is allowed with the override', allowed(null, '/home/u/seam', true));
check('the override never admits another sandboxed app', !allowed(info('org.example.Spy'), '/app/bin/seam', true));
check('the override needs the name', !allowed(null, '/usr/bin/seamless', true));

if (failed)
    imports.system.exit(1);
print('trust: all passed');
