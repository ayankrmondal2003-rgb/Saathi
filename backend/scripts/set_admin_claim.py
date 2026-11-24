"""One-time: give a Firebase account the {"admin": true} custom claim, so it can use /admin.

Run on your own computer with the Firebase service-account key (never commit it):

    cd backend
    .venv\\Scripts\\python.exe scripts\\set_admin_claim.py --key C:\\path\\to\\service-account.json --uid YOUR_UID
    .venv\\Scripts\\python.exe scripts\\set_admin_claim.py --key ... --uid YOUR_UID --remove

Your UID is in Firebase console -> Authentication -> Users. After setting the claim, sign out of /admin and in
again (ID tokens carry claims from when they were issued).
"""
import argparse

import firebase_admin
from firebase_admin import auth, credentials


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--key", required=True, help="path to the Firebase service-account JSON")
    parser.add_argument("--uid", required=True, help="Firebase UID of the reviewer")
    parser.add_argument("--remove", action="store_true", help="take the admin claim away instead")
    args = parser.parse_args()
    firebase_admin.initialize_app(credentials.Certificate(args.key))
    user = auth.get_user(args.uid)
    claims = dict(user.custom_claims or {})
    if args.remove:
        claims.pop("admin", None)
    else:
        claims["admin"] = True
    auth.set_custom_user_claims(args.uid, claims or None)
    # Existing sessions keep their old claims until refreshed; revoking forces a fresh sign-in.
    auth.revoke_refresh_tokens(args.uid)
    print(f"{'Removed' if args.remove else 'Granted'} admin for {user.email or args.uid}. Sign in to /admin again.")


if __name__ == "__main__":
    main()
