# Game Nuke source maintenance

Read REFACTOR_NOTES.md. Preserve package ID, signing certificate and version unless the user requests a release bump. Keep Android source private; never push it to the public website repository. Never expose tokens or signing secrets. Do not execute publishing tools without a release instruction.

Macro: NukeMacroEngine, NukeMacroService, NukeMacroSetupActivity. Socket utilities: NukeNetPacer. Updates: AppUpdateController (Google Play). Earlier feature and latency claims in this guide were not substantiated by the source.
