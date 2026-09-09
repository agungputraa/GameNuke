# Game Nuke — complete prompt implementation notes

## Delivery status

This source revision addresses all ten tasks in the supplied text, with the corrections below. All modified/new files are provided in full in the project ZIP and in MODIFIED_FILES_FULL.txt. Nothing was published, uploaded to Google Play, signed, or released. The package name, target SDK 36, version code and version name are preserved.

**Not a build-verified release.** The environment has no cached Gradle 8.13 distribution or Android SDK for this project. The previous compile attempt stopped before compilation because network access was unavailable. No new APK/AAB was built. Kotlin delimiter and static integration checks are not compilation, Android Lint, or device testing. The ten JVM tests are supplied, not claimed as executed.

## Task-by-task result

| Task | Implementation and important corrections |
|---|---|
| 1. Drawer | Navigation items and footer share a weighted scroll container; fixed 220 dp header becomes content-sized with 28 dp vertical padding; footer spacer is 24 dp; social row scrolls horizontally; macro drawer item removed. |
| 2. Bottom tabs | Core, Games and Optimize remain. Monitor tab and screen dispatch removed; dashboard monitor callback routes to dashboard; ad route set contains the three supported tabs. |
| 3. Accessibility activation | NukeAccessibilityActivator checks the exact component and service-enabled state, retries controller binding briefly, and opens explicit disclosure/setup when necessary. It does not write the system's enabled-service list through a shell. Silent activation was intentionally replaced with Android's user-controlled grant flow. |
| 4. Macro HUD card | MACRO card observes a stable, non-null state stream that also works when the service is disconnected. A real infinite transition pulses green while running. The sample's infiniteRepeatable passed to animateFloatAsState was replaced because those animation specifications are not interchangeable. |
| 5. Engine | SEQUENCE, BURST, PATTERN_LOOP and MULTI_SWIPE; snapshot isolation; finite repeats 1–9999; optional ±4 pixel offset; 5–30 requested burst CPS; callback-confirmed dispatch; cancellation; error state; action counts and measured CPS. |
| 6. Controller | Dark green Compose panel, draggable header, mode tabs, scrollable numbered pin editor, 10–500 ms delay slider, 1–200 ms hold slider, swipe toggle and 1–2000 ms swipe duration, speed chips, repeat stepper, loop switch, offset switch, live stats, pin deletion, pinned Run/Stop and status. Canvas pin markers pulse when selected; a separate non-touchable accessibility overlay draws dashed connectors. |
| 7A. Screenshot | Uses the existing authorized Shizuku/iAdb command path. Creates /sdcard/Pictures/GameNuke, captures a timestamped PNG and verifies a non-empty output before reporting success. Failure is reported accurately. Screenshots may include visible overlays; secure windows are not promised to be capturable. |
| 7B. Palm Shield | Visible 8 dp edge strips actually consume touches. They are not hardware palm classification. The suggested pointer_location command only controls a developer diagnostic display and was not used. The old anti-mistouch HUD action is redirected to the same real edge guard. State is persisted under palm_shield_on and cleared when windows are removed. |
| 7C. Crosshair | Card reads the real CROSSHAIR toggle and invokes onToggle. Existing service wiring persists cross_en and calls syncCrosshairOverlay to show/hide the actual view. Studio access elsewhere is retained. |
| 7D. Recent apps | Optional PACKAGE_USAGE_STATS declaration and an explanatory setup activity; user grants special access in Android settings. Query the last seven days, deduplicate by package, limit to four launchable recent apps, display icons, launch on tap. Panel closes on outside tap or after five seconds. No history is transmitted by this component. |
| 7E. Ping | Optional lifecycle-owned TCP connect probe to 8.8.8.8:53, approximately every two seconds plus probe duration, one-second connection timeout. Badge is green below 50 ms, yellow through 120 ms, red above 120 ms, gray on timeout. It is labeled TCP, not game-server latency. InetAddress.isReachable can fail or use unsuitable transport on ordinary Android apps, so a measurable socket operation is used instead. |
| 8. Theme | Shared Compose/native background, surface, card, accent, secondary text, border, error and warning tokens updated. Theme typography defines Outfit headings and Inter body styles. Material buttons/chips, custom click targets, central HUD cards and native button factories gain non-consuming press feedback; central HUD card components gain entrance motion. System dialogs and third-party SDK UI remain system/SDK owned. |
| 9. Policy audit | Removed permissions/services remain absent; macro service is system-bindable with BIND_ACCESSIBILITY_SERVICE and metadata; setup activities are not exported; macro label/description updated. No key-event filtering or screen reading capability. Source terminology checks included. These changes do not guarantee Play approval. |
| 10. English/OEM/API | All 249 Tx.t expressions converted to their English branch, language menu removed, translation network implementation removed, remaining selected local UI strings translated. Vendor overlay settings are best-effort with public Android fallback. Cutout mode uses SHORT_EDGES. Dynamic receivers use explicit NOT_EXPORTED. Immediate window removal paths are guarded. Exact-alarm scheduling checks special access first. |

## Required deviations from the supplied examples

1. **Accessibility consent:** No enableSilently/disableSilently implementation edits secure settings. Permission activation remains under the Android user's control. The normal MACRO action shows the separate disclosure if consent is missing. Consent version is v2 because tap/swipe/loop behavior changed. A previously granted v1 consent does not silently authorize the new version. Disabling uses the service's own disableSelf and clears macro consent.
2. **Accessibility metadata:** `typeNone` is not used as an XML enum. An omitted event subscription plus `serviceInfo.eventTypes = 0` represents no subscribed events. `flagRequestFilterKeyEvents` is not requested because the macro does not inspect keys. `canRetrieveWindowContent=false`, `canTakeScreenshot=false`, `canPerformGestures=true`, and `isAccessibilityTool=false` make scope explicit.
3. **SDK floor:** The uploaded project uses minSdk 30, not 26. It already relies extensively on Android 11 APIs and wireless debugging. MinSdk remains 30 to avoid falsely offering an unported Android 8–10 build. Supporting API 26 would require a separate compatibility migration and dependency/device testing. The requested Android 11+ focus is retained. Android 11–17/HyperOS/OneUI compatibility has not been certified by this environment.
4. **Foreground service types:** The gaming HUD continues to pass SPECIAL_USE on Android 14+. The web server retains its declared DATA_SYNC type, rather than passing an incompatible type. A timeout handler stops that service on newer Android versions.
5. **ULTRA delay:** ULTRA explicitly requests 8 ms, while manual delay sliders use 10–500 ms. The panel explains this exception. Android's actual dispatch time is not guaranteed to match a requested delay or requested CPS.
6. **Font files:** Real Outfit/Inter TTF bytes could not be downloaded here. No substitute font is mislabeled as Outfit/Inter. `app/nuke-fonts.gradle.kts` is applied by the app build and downloads the upstream variable TTF files and OFL licenses before resource merging. It generates the five requested resource names under `app/build/generated/nukeFonts/res/font`. The build fails explicitly on a failed or invalid download. Thus fonts are defined and automatically provisioned for a network-enabled build, but their binary files are not bundled in this ZIP. Variable weight selection uses the declared FontWeight entries. The upstream files are fetched from Google Fonts main; pin an approved revision for a frozen production supply chain.

## Macro execution details

- SEQUENCE repeats the full list; BURST performs the first pin for the requested number of touches; LOOP repeats the list until Stop; SWIPE drags from a pin with Swipe enabled to its next pin, otherwise it taps. The final pin is tapped.
- Action count includes completed taps and swipes. CPS reports completed actions over a recent interval, not a fabricated target rate. No catch-up burst is used after a slow dispatch.
- The editor is disabled during playback. Pin markers and connectors are hidden during playback to avoid intercepting the gesture. The Run/Stop row stays outside the scrolling editor.
- Pin placement is validated against display bounds and the controller. Offset-enabled runs reserve four pixels at the edges. Swipe bounding boxes are conservatively rejected if they overlap the controller; reposition the controller to avoid that path.
- Stop prevents future dispatches. A gesture already accepted by Android may finish its hold/swipe duration; swipes can last up to two seconds. A new Run is refused while the previous framework gesture is still in flight. Closing the controller does not pretend to cancel a framework gesture already submitted.
- Rotation/configuration changes, screen-off, service interruption, consent revocation and ending the owning HUD session clear controller windows and session coordinates. No automatic macro restart occurs. Reposition pins after orientation changes.
- The macro does not read the foreground game's UI or subscribe to window events, so it cannot infer that a user has switched to another app. Stop it before manually leaving the game. App Switch and Screenshot stop playback before their action.
- Coordinates are session-only; profile import/export, saved presets, recording, multi-finger concurrent touches and semantic screen recognition are not introduced by this prompt.

## Build and test instructions

On a machine with Android Studio, JDK 17, Android SDK 36 and allowed Gradle/Maven/Google Fonts network access:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Windows:

```bat
gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

`prepareNukeFonts` is a prerequisite for preBuild and resource merges. It downloads two font families and generates the five named TTF resources plus licenses. Generated resources are kept out of source control. No signing secrets are changed; release signing remains the owner's responsibility.

Ten JVM engine tests cover: sequence order/repeats, immutable snapshots, stopping/concurrent-start prevention, rejected gestures, invalid input, burst first-point behavior, infinite-loop cancellation, swipe endpoints/duration, bounded coordinate offset and recovery after error. They have not been executed here.

Run the included `python3 tools/validate_refactor.py` for lightweight source integration checks. It does not replace the Gradle tasks above.

## Device acceptance checks still required

- POCO X6 Pro / HyperOS Android 14 first: small display width, large font scale, portrait/landscape, navigation gestures, cutout and popup permission handling.
- Refuse/accept/revoke Accessibility consent; enable externally without app consent; delayed service binding; process recreation; controller close; screen lock during tap and swipe.
- Every engine mode, requested versus achieved CPS, stop during an in-flight gesture, invalid pin coordinates, swipe near the control panel, repeat count boundaries and long loop memory/battery behavior.
- Screenshot with authorized and absent shell access, unwritable capture destination and secure game surface; never report a file as saved on failure.
- Palm Shield enable/disable, rotation, end session, system navigation edges and process death. Eight dp visible strips are a deliberate touch-blocking region.
- Recent apps access absent/granted/revoked, empty results, stale uninstalled apps, outside tap, timeout and launch failure.
- TCP monitor online/offline, blocked endpoint, mobile/Wi-Fi transition, screen-off and service end. The number is endpoint connect timing, not ICMP/game latency.
- Play update through an internal test track; inspect merged release manifest, accessibility declaration, SDK Data Safety and foreground-service declaration. Review the retained ADB/Shizuku/system-tuning, ads, microphone, local REST and background-chat features before any Play submission.

## Official references

- Accessibility automation and disclosure: https://support.google.com/googleplay/android-developer/answer/10964491?hl=en
- Accessibility service metadata: https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo
- Gesture dispatch: https://developer.android.com/reference/android/accessibilityservice/AccessibilityService
- Google Fonts Inter source: https://github.com/google/fonts/tree/main/ofl/inter
- Google Fonts Outfit source configured for build: https://github.com/google/fonts/tree/main/ofl/outfit
