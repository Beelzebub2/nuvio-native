# Opt-in hard download storage quotas

The **Downloads → Storage** panel offers a per-profile storage target. Targets
are **informational by default** and show a warning when tracked offline media
exceeds them. There is no background deletion.

On Desktop and Android, after choosing a storage target, **Enforce download
limit** turns that warning into an opt-in transfer budget. This switch is
independent, off by default, saved locally per profile and turned off when the
target is cleared. iOS has no storage manager and does not show the option.

## Enforcement rules

1. Downloaded bytes of all tracked items count toward the active profile's
   budget, including completed, failed and partial downloads. Running items
   reserve their entire remaining expected size once the transfer engine
   reports a valid total size. Arithmetic saturates at the maximum 64-bit
   integer instead of wrapping.
2. A queued download is paused before starting if the existing usage already
   exhausts the limit, or if its known size would overshoot the limit.
3. Streams with an unknown final size may start only while below the limit.
   Once a total size or more bytes are reported, the transfer is cancelled and
   marked paused if it would exceed the selected budget. Progress callbacks are
   granular, so unknown-size transfers can **briefly overshoot**. The quota is
   a best-effort application-level guard, not a filesystem-level hard barrier.
4. Changing the quota while downloads are active immediately re-evaluates their
   reservations and pauses transfers when needed. No paused transfer
   automatically resumes if the limit increases or is disabled; users opt in
   again with the normal **Resume** action.
5. The app never deletes files or runs a background cleanup task to satisfy a
   quota. The existing explicit cleanup preview, ownership validation,
   symlink/path checks and confirmation remain independent.

## Boundaries

- Desktop tracks the currently loaded profile's task handles. Account/profile
  switches refresh the policy and invalidate old handles as before.
- Android persists the originating profile with each scheduled transfer and
  enforces that profile's budget in the worker, including after process
  recreation or while another profile is displayed. Reservations include the
  profile's saved queue plus its current durable transfer records. The worker
  checks response headers and every progress callback; unknown sizes may
  overshoot by a read buffer. Account sign-out clears quota targets and opt-ins.
- The optional quota does not modify torrent/P2P engines, their network
  behavior, source selection or file naming. It uses the existing task's
  cancellation/resume pathway.
- Unmanaged files and external operating-system storage usage cannot be
  inferred from app download records. The separate available-space indicator
  warns about a low-space volume, but no absolute free-space guarantee is made.
- The profile target lives in the existing local storage budget store; the
  new enforcement flag uses a separate per-profile preference key.

## Cleanup safety

Age and least-recently-watched actions only select eligible entries in the
preview. Cleanup remains off until the user opens the preview and confirms
the exact selected files. No policy deletes files without confirmation.

Desktop eligibility requires a parsed completion proof matching the task,
filename and size, plus the existing cached content verification. The storage
screen never hashes large media. Changed/unverified files are excluded until
the existing background verifier verifies them again. Confirmed cleanup
rechecks the active profile, running transfer, paths and proof under the
transfer's IO lock immediately before removal; changed files are preserved.

Android eligibility additionally requires a matching completed durable
transfer record owned by the current profile, inside the app's private
downloads directory. Legacy files without a recorded profile are preserved
by this cleanup flow. A new transfer cannot claim an existing untracked final
file or another transfer's filename.

These checks do not make application cleanup a filesystem transaction against
concurrent OS-level modification. Permission errors fail closed; validate
volume permissions and the UI on real Desktop and Android devices.

## Verification

The targeted `DownloadStorageManagementTest` suite covers known and unknown
sizes, concurrent reservations, exhaustion, overflow, age/LRU selection and
review-only cleanup. Test Android background download cancellation and resume,
Desktop large-file transfers, changing profile while a transfer is active,
low-space devices and restart behavior on physical devices before merging.
