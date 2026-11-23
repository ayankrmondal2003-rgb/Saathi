# Maitri checkpoint B

Screens 09–11 use the approved Light and Dark crop geometry and icons with native text, search, selection and touch controls. Screen 09 opens Edit (14), Language (10), Test vibration (11), Permissions (12), Safety PIN (13), the theme selector, Android notification settings, privacy controls and bundled help.

Screen 10 persists English, Hindi, Bengali, Marathi, Tamil or Telugu. Screens 09–11 resolve translated Android resources for the saved language. Screen 11 defaults to Steady and plays the selected Gentle, Steady or Strong waveform through Android’s vibrator, with timing-only fallback on hardware without amplitude control.

Local verification: debug APK built, 110 JVM tests and 8 device tests passed with no skips. Device checks covered profile navigation in both themes, all six saved languages and all three vibration controls. All six Compose previews rendered. Visual review did not include an automated pixel diff. Screens 01–08 retained their implementations. No push or deployment.
