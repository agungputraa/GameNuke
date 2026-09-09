from pathlib import Path
import re,json
root=Path(__file__).resolve().parents[1]
src=root/"app/src/main/java/com/neon/gametweak"
def quoted(s,i):
 if s.startswith('"""',i):
  j=i+3
  while j<len(s):
   if s.startswith('"""',j):return j+3
   if s.startswith('${',j):j=balanced(s,j+1)[0]+1
   else:j+=1
  raise ValueError('raw string')
 q=s[i];j=i+1
 while j<len(s):
  if s[j]=='\\':j+=2
  elif q=='"' and s.startswith('${',j):j=balanced(s,j+1)[0]+1
  elif s[j]==q:return j+1
  else:j+=1
 raise ValueError('string')
def balanced(s,i):
 close={'(':')','[':']','{':'}'}[s[i]];j=i+1;commas=[]
 while j<len(s):
  if s.startswith('//',j):j=s.find('\n',j);j=len(s) if j<0 else j
  elif s.startswith('/*',j):
   level=1;j+=2
   while level:
    if s.startswith('/*',j):level+=1;j+=2
    elif s.startswith('*/',j):level-=1;j+=2
    else:j+=1
  elif s[j] in '\"\'':j=quoted(s,j)
  elif s[j] in '([{':j=balanced(s,j)[0]+1
  elif s[j]==close:return j,commas
  elif s[j]==',':commas.append(j);j+=1
  else:j+=1
 raise ValueError('unclosed '+s[i:i+60])

failures=[];count=0
for p in (root/'app/src').rglob('*.kt'):
 s=p.read_text();i=0
 try:
  while i<len(s):
   if s.startswith('//',i):
    n=s.find('\n',i);i=len(s) if n<0 else n
   elif s.startswith('/*',i):
    level=1;i+=2
    while level and i<len(s):
     if s.startswith('/*',i):level+=1;i+=2
     elif s.startswith('*/',i):level-=1;i+=2
     else:i+=1
    assert level==0,'comment'
   elif s[i] in '\"\'':i=quoted(s,i)
   elif s[i] in '([{':i=balanced(s,i)[0]+1
   elif s[i] in ')]}':raise ValueError('unexpected closing delimiter at '+str(i))
   else:i+=1
  count+=1
 except Exception as e:failures.append((str(p),str(e)))
main=(src/'MainActivity.kt').read_text();hud=(src/'FloatingHudCompose.kt').read_text();engine=(src/'NukeMacroEngine.kt').read_text()
assert main.count('NavigationBarItem(')==3
assert 'showLangMenu' not in main and 'Icons.Rounded.Translate' not in main
assert 'DrawerItem(Icons.Rounded.TouchApp' not in main
assert all(k in hud for k in ['"app_switch"','"ping_monitor"','"MACRO"'])
assert '"screenshot"' not in hud and '"palm_shield"' not in hud
assert 'Icons.Outlined.PhotoCamera' not in hud and 'Icons.Outlined.PanTool' not in hud
assert all(k in engine for k in ['SEQUENCE','BURST','PATTERN_LOOP','MULTI_SWIPE','FAST_GLOO','WEAPON_SWITCH','AUTO_TAP'])
assert not any('Tx.t(' in p.read_text() for p in src.rglob('*.kt'))
assert 'settings put secure' not in (src/'NukeAccessibilityActivator.kt').read_text()

service=(src/'NukeMacroService.kt').read_text(); cross=(src/'NukeCrosshairView.kt').read_text(); magic=(src/'NukeMagicTouchPanelOverlay.kt').read_text()
assert 'pins.clear()' not in service.split('private fun closeController()',1)[1].split('private fun shutdownOverlays()',1)[0]
assert 'NukeMacroPersistence' in service and 'restorePinsForSession' in service
assert all(k in cross for k in ['DOT','CLASSIC_CROSS','CIRCLE_DOT','CHEVRON','SNIPER_T','BOX_BRACKET','DYNAMIC_GAP'])
assert '480Hz' not in magic and 'setprop touch.pressure.scale' not in magic and 'view.touch_slop' not in magic
fonts=(root/'app/nuke-fonts.gradle.kts').read_text(); gradle=(root/'app/build.gradle.kts').read_text()
assert 'AppExtension' not in fonts and 'sourceSets.getByName("main")' in gradle
assert 'agwallpaper84.jks' in gradle and '"agwallpaper"' in gradle
assert not any((src/n).exists() for n in ['NukeVpnService.kt','NukeVpnTrampolineActivity.kt'])
print(json.dumps({'kotlin_files_delimiter_checked':count,'delimiter_failures':failures,'navigation_and_integration_checks':'passed'},indent=2))
assert not failures
