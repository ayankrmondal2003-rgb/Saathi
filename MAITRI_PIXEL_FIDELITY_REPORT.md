# Pixel fidelity — checkpoint 2

New Home comparison: PARTIAL. Both native Compose Home previews rendered and were visually inspected against corrected Light/Dark screen-02 PNGs. Extracted stroke paths, backgrounds, cards and ring colors are used. Body font remains provisional; no pixel-identical claim. Preview excludes system bars and bottom navigation, so it is not yet a matched full-screen handset comparison.
At 390dp width the native SOS card is approximately 298dp tall versus 292dp reference; native text metrics and the Manage button minimum touch target alter vertical flow. These remain to be corrected without sacrificing accessible hit targets.
The corrected dark-02 reference render has an empty central SOS disk, whereas the Light reference contains shield/SOS/Get help. The functional Dark implementation currently keeps those readable controls. Verify original dark vector contents before declaring this intentional design; record it as a remaining visual discrepancy, not a verified match.
All 28 derived SVGs were missing original root fill="none". This caused black-filled outline icons. Restored inheritance and regenerated reference PNGs; original authoritative SVG boards untouched.
Screens 03–14 have not been ported.

## Retained checkpoint 1 evidence
Status: PARTIAL, not pixel-identical certification.

VERIFIED: Both original boards are readable; all 28 independent reference crops rendered at 780 × 1688. Both full boards and both screen-01 crops were viewed. Original logo paths/gradients and theme-specific wordmarks were converted into Android vectors. Compose previews for screen 01 rendered in Light and Dark.

Observed differences:
- Body font is unconfirmed. Default Android text is provisional, not an identified match. In the Light preview the supporting line spans approximately 150dp versus roughly 161dp in the SVG. Candidate font comparison and licensed font selection remain pending.
- Preview content excludes reference's 44px status and 34px gesture area. Android uses genuine system bars, never fake 9:41/battery artwork.
- Initial preview button top is about 640dp in content coordinates versus reference target 648dp; native text metrics affect vertical flow. This must be corrected with font/baseline validation.
- Large text/small height uses native scrolling and expands text/button content to remain accessible.
- No objective pixel-difference score or matched physical screenshot has been produced.
- Screens 02–14 still use the existing application UI. Their 26 Compose comparisons are not completed.

Do not use the initial incorrectly cropped design-reference/light-01.png as a reference; correct reference is design-reference/crops/light-01.png. Source boards are untouched.
