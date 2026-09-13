#!/bin/bash
SRC="c:/ProyekAndroid/GameNukePrem/app/src/main"
echo "=== PinTriggerMode usages ==="
grep -rn "PinTriggerMode" "$SRC" --include=*.kt | grep -v "NukeMacroModel.kt" | head -40
echo "=== MacroPinConfig usages ==="
grep -rn "MacroPinConfig" "$SRC" --include=*.kt | grep -v "NukeMacroModel.kt" | head -40
echo "=== savePins/loadPins/clearPins usages ==="
grep -rn "NukeMacroRepository" "$SRC" --include=*.kt | head -40
echo "=== MacroRunState usages ==="
grep -rn "MacroRunState\|NukeMacroEngine\." "$SRC" --include=*.kt | head -40