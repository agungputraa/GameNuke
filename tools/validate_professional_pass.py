#!/usr/bin/env python3
from pathlib import Path
import re, sys, xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/com/neon/gametweak'
failures=[]
passes=[]

def check(name, condition, detail=''):
    (passes if condition else failures).append((name, detail))

manifest = ROOT/'app/src/main/AndroidManifest.xml'
try:
    ET.parse(manifest); check('Manifest XML parses', True)
except Exception as e: check('Manifest XML parses', False, str(e))

build=(ROOT/'app/build.gradle.kts').read_text(errors='ignore')
check('compileSdk 36 retained', 'compileSdk = 36' in build)
check('targetSdk 36 retained', 'targetSdk = 36' in build)
check('minSdk 30 retained', 'minSdk = 30' in build)
check('modern JNI packaging retained', 'useLegacyPackaging = false' in build)

hud=(SRC/'FloatingHudCompose.kt').read_text(errors='ignore')
svc=(SRC/'FloatingBoosterService.kt').read_text(errors='ignore')
check('Exactly one Task Manager launcher', hud.count('onQuickAction("task_manager")') == 1, str(hud.count('onQuickAction("task_manager")')))
for action in ['task_manager','magic_touch','deep_clean','ai_sentinel','phone_health','deep_cooling','antivirus','fps_lock','footstep_boost','net_boost','crosshair_studio']:
    check(f'Handler retained: {action}', bool(re.search(r'"'+re.escape(action)+r'"\s*->', svc)))

# Brand/UI checks; internal class and JSON schema names are intentionally not renamed.
all_ui='\n'.join(p.read_text(errors='ignore') for p in SRC.rglob('*.kt'))
for label in ['MAGIC TOUCH','AI SENTINEL','TASK MANAGER','DEVICE HEALTH','DEEP CLEAN','CROSSHAIR','PLUGIN']:
    check(f'Professional UI label present: {label}', label.lower() in all_ui.lower())

for forbidden in [
    '+512MB RAM Optimized', '+1.2GB Cache Purged', '0ms Edge Lag', '2.8ms Latency',
    'FF HEADSHOT', 'CQB SHOTGUN', 'SNIPER AIM', 'KILL ROGUE APPS',
    'SYSTEM HEALTH GRADE', 'UFS High-Speed', 'Flash Wear Normal',
]:
    check(f'No fabricated UI claim: {forbidden}', forbidden.lower() not in all_ui.lower())

phone=(SRC/'NukePhoneHealthOverlay.kt').read_text(errors='ignore')
check('Device Health has no fake initial 98% score', 'text = "98%"' not in phone)
check('Device Health has no fake initial battery temp', 'createCardText("33.0°C")' not in phone)
check('Device Health has no fake UFS 4.0 label', 'STORAGE (UFS 4.0 FLASH)' not in phone)

module_screen=(SRC/'ui/screens/NukeModuleShopScreen.kt').read_text(errors='ignore')
check('Plugin Center visible name', 'PLUGIN CENTER' in module_screen)

print(f'PASS={len(passes)} FAIL={len(failures)}')
for name, detail in passes:
    print(f'[PASS] {name}' + (f' :: {detail}' if detail else ''))
for name, detail in failures:
    print(f'[FAIL] {name}' + (f' :: {detail}' if detail else ''))
sys.exit(1 if failures else 0)
