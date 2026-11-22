# Remaining implementation — checkpoint 2
Status: PARTIAL. Welcome preserved; Home and navigation implemented incrementally.

Completed since checkpoint 1:
- Native Home Light/Dark with exact extracted stroke icons and measured colors.
- Home/Circle/Responder/Profile navigation order; internal Contacts route preserved.
- Elapsed-time three-second hold with cancellation and no duplicate release callback.
- Explicit controlled-test confirmation; GPS sharing defaults off.
- Samsung isolated tests for hold, confirmation, consent and tab order passed.
- Corrected missing root fill="none" in all derived SVG crops. Original boards unchanged.

NEXT EXACT STEP: capture matched Home handset screenshots including navigation/system insets; correct measured text/spacing differences. Then inspect light-03.svg/png and dark-03.svg/png, read EntryScreens.kt, AuthViewModel.kt and FirebaseAuthRepository.kt, and port login without replacing Firebase/session behavior.
Do not redo Welcome or infer that trip/check-in controls are complete. Their transient unavailable explanations are safe boundaries only.
Old Home/legacy EmergencySosButton remain for compatibility; production Home navigation uses MaitriHome. Legacy two-second tests do not verify the new recognizer; MaitriHomeTest does.

Remaining implementation order (the first Home/navigation portion of item 3 below is now done):

Resume in this order:
1. Resolve welcome body font through Figma metadata if an actual file link becomes available, otherwise compare licensed candidates against outlined glyphs. Correct baseline/width differences and capture matching handset/reference images.
2. Inspect individual crops 02–14 in both themes. Finish the per-control audit against all requested Android classes, backend services, models and migrations.
3. Port Home and navigation together: Home → Circle → Responder → Profile. Change the real hold recognizer and cancellation tests from two to three seconds; maintain stable server idempotency.
4. Port auth and circle, preserving verified identity, account isolation and real backend decisions. Add phone OTP only with matching backend identity policy and abuse controls.
5. Port responder/active incident and PIN, preserving authorization and revocation. Never display sample people, locations or route times as real.
6. Add settings, actual locale support and five permission states. Keep caller account and active incident intact during theme/locale/navigation changes.
7. Implement separate secure trip/check-in models with migrations, transactions, retention and delivery-attempt state. Do not reuse SOS.
8. Add volunteer/media/profile integration boundaries with private storage and reviewer controls; do not collect sensitive uploads until infrastructure is verified.
9. Finish branding beyond welcome, all screen tests, 28 matched screenshots, full isolated backend regressions and controlled cloud/phone verification.

No backend or migration files changed in checkpoint 1. No push, commit or Render rollout performed.
