# Maitri SVG inventory — checkpoint 1

Status: PARTIAL. Original files are accessible and preserved under design-reference. Both full boards were rendered and viewed. Screens 01 and 02 were individually inspected in both themes; individual control/geometry inspection of screens 03–14 remains pending. Checkpoint 2 restored original fill="none" inheritance in all derived crops and regenerated PNGs, correcting previously black-filled outline icons.

Audited Git starting revision: 8e7cc88a1396b1eefc496e81de8b7dd73e0cbee7.

Both originals are 3034 × 1856, with fourteen top-level screen groups and zero editable text elements. Frames are 390 × 844. All 28 groups were extracted with original definitions and rendered at 780 × 1688 by resvg-js-cli 2.6.2-beta.1. Full boards are rendered at 6068 × 3712. Crops are references only, never app backgrounds.

| Screen | Board position x,y | Screen | Light / Dark reference |
|---|---|---|---|
| 01 | 32,48 | Welcome | crops/light-01.png / crops/dark-01.png |
| 02 | 462,48 | Home / SOS | crops/light-02.png / crops/dark-02.png |
| 03 | 892,48 | Login / sign up | crops/light-03.png / crops/dark-03.png |
| 04 | 1322,48 | Volunteer verification | crops/light-04.png / crops/dark-04.png |
| 05 | 1752,48 | Your circle | crops/light-05.png / crops/dark-05.png |
| 06 | 2182,48 | Nearby SOS request | crops/light-06.png / crops/dark-06.png |
| 07 | 2612,48 | Responder on your way | crops/light-07.png / crops/dark-07.png |
| 08 | 32,972 | SOS is active | crops/light-08.png / crops/dark-08.png |
| 09 | 462,972 | Profile & settings | crops/light-09.png / crops/dark-09.png |
| 10 | 892,972 | Language | crops/light-10.png / crops/dark-10.png |
| 11 | 1322,972 | Test vibration | crops/light-11.png / crops/dark-11.png |
| 12 | 1752,972 | Permissions | crops/light-12.png / crops/dark-12.png |
| 13 | 2182,972 | Safety PIN | crops/light-13.png / crops/dark-13.png |
| 14 | 2612,972 | Edit profile | crops/light-14.png / crops/dark-14.png |

Screen names were identified from the rendered boards. Full per-control audit remains a separate unfinished deliverable.

Screen 01 extracted colors: Light background #FFF9FA, text #40303A, secondary #7D6771, accent #B82055, rings #FFF0F5 / #FCE8ED. Dark background #19151B, text #FAEFF5, secondary #C3ADBA, accent #FF9CBE, rings #241B25 / #36232F. Original logo retains its six SVG gradients in both themes.

Original body/UI font is unconfirmed. Owner explicitly prohibited assuming a font identity. Logo and wordmark are exact extracted paths; ordinary text is native and its metrics are provisional. No Figma URL or file key has been provided.

Re-render a crop with:
`npx --yes @resvg/resvg-js-cli@2.6.2-beta.1 --no-system-font design-reference/crops/light-01.svg design-reference/crops/light-01.png`
