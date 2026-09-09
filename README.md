# Game Nuke Android source

Kotlin / Compose. Package com.neon.gametweak. Min SDK 30, target SDK 36.

Read REFACTOR_NOTES.md and CHANGESET.txt. This revision adds the four-mode floating macro controller and updates HUD tools, navigation, English text and compatibility handling. It has not been build- or device-verified in the editing environment.

Use JDK 17, Android SDK 36 and network-enabled Gradle. Run ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug. The build task prepareNukeFonts retrieves real Outfit/Inter font resources and their licenses before resource merging.

Keep source private and preserve signing identity. No Play approval or release readiness is implied.
