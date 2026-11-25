# Maitri beta — guide for testers

Thank you for helping test **Maitri** (version 0.9.4-beta.5). Maitri lets you send an SOS to the people you trust,
share your live location with them, and lets verified volunteers nearby offer help.

> **This is a beta, and the SOS is real.** When you hold the SOS button, the people in your circle get an urgent
> alert with your live location. Please only test SOS with friends who know you're testing, and tell them first.
>
> **In a real emergency, call 112.** Maitri is not a replacement for emergency services.

## 1. Install (5 minutes)

1. Open the invite email from **Firebase App Distribution** on your Android phone (Android 8 or newer) and tap
   **Get started**. Sign in with the Google account the invite was sent to.
2. Tap **Download** next to Maitri 0.9.4-beta.5. If Android asks, allow your browser to **install unknown apps**
   (Settings asks once; you can turn it off again afterwards).
3. Open **Maitri** and tap **Get started**, then **Continue with Google** (or use email and password).

New versions arrive the same way: you'll get an email, and the App Tester app shows an update.

## 2. Set up (5 minutes)

1. **Profile › Permissions:** allow **Location** (then *Allow all the time* if offered), **Notifications** and
   **Microphone**. Without notifications you won't receive alerts.
2. **Profile › Safety PINs:** choose a 6-digit **safety PIN**. You need it to end an SOS, so a phone thief can't
   switch it off. Don't use your birthday.
3. **Circle › Add contact:** add at least one person. If they also use Maitri, share your **invite code** with them
   (they enter it under *Circle › Enter a code from a friend*), so they get alerts inside the app. Contacts without
   Maitri get a text message that you send from your own messaging app.
4. **Keep alerts on time** (Profile › Permissions › *Keep Maitri alerts on time*). Phones put apps to sleep to save
   battery, which can delay an SOS alert. On your phone:
   - **Samsung:** Settings › Apps › Maitri › Battery › **Unrestricted**.
   - **Xiaomi / Redmi / POCO:** Settings › Apps › Manage apps › Maitri › **Autostart on**, and Battery saver ›
     **No restrictions**. Also open Recents, long-press Maitri and tap the **lock**.
   - **Oppo / Realme / OnePlus:** Settings › Battery › App battery management › Maitri › **Allow background
     activity**, and Apps › Maitri › **Auto launch on**.
   - **Vivo / iQOO:** Settings › Battery › Background power consumption › Maitri › **Allow**, and i Manager ›
     Autostart › Maitri **on**.
   - **Others:** Settings › Apps › Maitri › Battery › **Unrestricted** (or *Don't optimise*).

## 3. What to try

Please try these with a friend who also has Maitri, and note anything confusing or broken:

| # | Try this | What should happen |
|---|---|---|
| 1 | Lock your friend's phone. Hold **SOS** on yours for 3 seconds | Their phone shows an urgent alert, even locked |
| 2 | They open the alert in the bell (inbox) | Your live location, and a button to open it on a map |
| 3 | During the SOS, record a **voice note** and take a **photo** | They can play the voice note and see the photo |
| 4 | Add a contact who doesn't have Maitri, start an SOS and tap **Text circle** | Your messaging app opens with a live-location link; it shows a map while the SOS is on |
| 5 | **End SOS** with your safety PIN | They get "… ended their SOS" |
| 6 | **Share my trip** with your friend, walk a bit, then stop | They can follow you until you stop |
| 7 | **Check in** with a time 2 minutes away, then don't check in | You get a reminder; then they're told you missed it |
| 8 | **Profile › Language** — try Hindi, Bengali, Marathi, Tamil or Telugu | The app switches language |
| 9 | Phone **Settings › Display › Font size** at the largest | Text gets bigger and nothing is cut off |
| 10 | **Responder** tab — *Yes, I'd like to help*, upload documents | Your volunteer application is submitted for review |
| 11 | Approved volunteers: turn **Available for nearby requests** on and tap **Get requests when Maitri is closed** | After choosing *Allow all the time*, you get nearby SOS requests even with Maitri closed |

The translations were drafted by machine. If you read one of those languages, wrong or awkward wording is very
useful feedback.

## 4. Good to know

- **The first action after a quiet spell can take up to a minute** while the free server wakes up. An SOS keeps
  retrying on its own in the background; you'll see "Connecting to Maitri…".
- **At most 3 SOS per hour** per account, to prevent accidents and misuse.
- **Who sees what during an SOS:** your circle sees your live location, voice notes and photos. Volunteer responders
  who accept see your location and, only if you allow it, your phone number and medical notes. Nobody else does.
- **Crash reports** are sent automatically (the error, app version and phone model — never your location or
  details). You can turn them off in *Profile › Privacy & data*.

## 5. Report a problem

- Easiest: *Profile › Help & support › Beta info › **Send feedback***. It opens an email with the app version and
  your phone model already filled in. Or write to **ayankr.mondal6296@gmail.com**.
- Please say what you did, what you expected and what happened, and add a screenshot if you can.
- Something urgent, like an SOS that didn't arrive? Say so in the subject line.

## 6. Your data

- Privacy policy and terms: links in *Profile › Privacy & data*.
- **Export my data** or **Delete my account** at any time in *Profile › Privacy & data*. Deleting removes
  your account, your files and your sign-in. SOS locations are erased 7 days after an SOS ends, and voice notes and
  photos after 30 days.
- When the beta ends, we'll tell you what happens next. You can uninstall Maitri at any time.
