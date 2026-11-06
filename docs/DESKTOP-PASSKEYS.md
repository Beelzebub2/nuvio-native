# Sign in on Windows with a phone passkey

Register a passkey from **Settings → Account** in the Starlit Android app while
signed in to your approved Starlit account. Then, on desktop, select **Sign in
with passkey** and choose **iPhone, iPad or Android device** (or **Use another
device**) in the Windows security dialog. Scan the QR code with your phone and
approve using your phone's screen lock, fingerprint or face. Keep Bluetooth
enabled on both devices and keep the phone nearby for the proximity check.

This uses Windows' native WebAuthn hybrid transport, supported by WebAuthn API
version 6 and later (current Windows 11). The app hides the passkey button on
older Windows, macOS and Linux. Passkey registration remains available on
Android. Google sign-in and password sign-in remain available as configured.

The phone retains the private key. Windows returns only a signed assertion for
the official `starlit-api.rrmtools.uk` relying party; Starlit's existing GoTrue
endpoints validate the original one-use challenge, signature and approved
account before issuing the normal session. Desktop additionally requires the
signed user-presence and user-verification flags, even if the server advertises
verification as preferred. No browser
bridge or local WebAuthn origin is involved. Cancelling or leaving the screen
aborts the native operation and never imports a session.

Native declarations follow Microsoft's
[WebAuthn header](https://github.com/microsoft/webauthn/blob/master/webauthn.h).
Real-device validation still requires scanning the Windows-generated QR code
and unlocking a phone holding an existing Starlit passkey.
