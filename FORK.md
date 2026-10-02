# Kraaft fork of react-native-background-downloader

This fork exists so the Kraaft app can fix the library's Android notification
behaviour from source instead of carrying a patch file. It replaces the
published `@kesha-antonov/react-native-background-downloader` tarball
entirely; the package name is unchanged, so nothing downstream has to know.

The app wires it in as a pnpm workspace package — the submodule sits at
`frontend/packages/react-native-background-downloader`, is matched by the
`frontend/packages/*` glob in `pnpm-workspace.yaml`, and is depended on with
`workspace:*` from `frontend/packages/download-manager`, `frontend/package.json`
and `frontend/native/package.json`. Because it resolves as an ordinary node
package, iOS CocoaPods autolinking and Android Gradle autolinking pick it up
with no further configuration: no composite build, no `:path` override, and no
`fingerprint.config.js` entry.

Unlike `firebase-android-sdk`, this repo is **not stripped** — it is small, and
`example/` is how a change is verified without rebuilding the Kraaft app.

Its README says to `yarn install` here first; inside the monorepo do not — pnpm
already installed this package and built `lib/`, and yarn would fight it. Only
`cd example && yarn install` is needed, then `npx expo prebuild --platform
android` and `cd android && ANDROID_SERIAL=<serial> ./gradlew installDebug`
(`expo run:android --device <serial>` does not resolve a serial). The example's
own `node_modules` nests a second react-native inside the workspace, so the
Kraaft app's `native/metro.config.js` blocks that path from haste.

## Branches

- `kraaft/v4` — what the app builds. Based on `main` at 4.6.3, plus the
  changes below.
- `main` — untouched upstream mirror, kept for diffing and for rebasing onto a
  newer release. `upstream` points at `kesha-antonov/react-native-background-downloader`.

The fork carries no tags. `main` happened to sit exactly at 4.6.3 when it was
taken, which is why `kraaft/v4` branches from it directly.

## Kraaft patches

### One notification per download group, not per file

`android/src/main/java/com/eko/uidt/UIDTNotificationManager.kt`,
`android/src/main/java/com/eko/UIDTDownloadJobService.kt`

On Android 14+ each download becomes its own user-initiated data transfer job,
and the platform requires every such job to carry a visible notification. A
batch therefore filled the shade with one entry per file. `summaryOnly` mode
could not prevent it: those notifications are posted by the JobScheduler
through `setNotification`, not by `NotificationManager`, so the group key the
library set on them never applied, and the aggregate it posted separately was
just one more entry beside them.

Verified on a Samsung SM-A566B (Android 16) with this repo's `example/`, five
downloads started at once, `showNotifications` + `grouping` + `summaryOnly` all
on: upstream posts seven notifications (ids `86310`-`86314`, one per file, plus
two summaries), the fork posts one (`303214`).

`UIDTNotificationIds` is what kept them apart — it exists to guarantee each
download a *distinct* notification id. Jobs of one group now share an id
instead, which is the documented way to collapse them:

> If separate jobs use the same notification ID, the most recently provided
> notification will be shown to the user, and the job end policy of the last
> job to stop will be applied.

Two consequences had to be handled. The aggregate progress is pushed onto that
shared notification through `setNotification` rather than posted beside it, so
there is one notification rather than two. And only the last job of a group may
apply `JOB_END_NOTIFICATION_POLICY_REMOVE`, or the first file to finish takes
the notification down for every file still transferring — the exact failure
`UIDTNotificationIds`' own header comment describes.

### `groupText` is honoured when a total is known

`android/src/main/java/com/eko/uidt/UIDTNotificationManager.kt`

`updateSummaryNotificationWithProgress` hardcoded `"$progress% - N files"`
whenever a byte total was known, falling back to the configured `groupText`
only while it was not. In practice that meant the configured string showed for
the instant before the first `Content-Length` arrived and the hardcoded English
one for the rest of the download — so the text users actually read was neither
configurable nor translatable. Both cases now go through the template, with
`{progress}` available alongside `{count}`.

Note that `NotificationTexts.groupText` accepts `string | ((count: number) =>
string)`, but the function form never reaches Android:
`getNotificationTextsForNative` discards it and substitutes the English
default. Pass a plain string.

### One job per group, not per file

`android/src/main/java/com/eko/uidt/UIDTJobManager.kt`,
`android/src/main/java/com/eko/uidt/UIDTJobIds.kt`,
`android/src/main/java/com/eko/UIDTDownloadJobService.kt`

One job per file does not scale past the per-app job limit: `scheduleDownload`
refused once the app held 120 pending jobs (the platform allows 150 from Android
12, the library keeps 30 in reserve) and fell back to the foreground service — a
second mechanism with a second notification. That limit is shared with everything
else the app schedules, WorkManager included.

A grouped download now joins its batch's single job through
`JobScheduler.enqueue(JobInfo, JobWorkItem)`, dequeued with
`JobParameters.dequeueWork()` and retired with `completeWork()`. One job and one
notification cover the batch whatever its size. Concurrency stays the existing
`maxParallelDownloads`, which `ResumableDownloader` already enforces.

Batching applies only to `summaryOnly` grouping, the mode that asks for a single
notification; the others keep a job per download, because the platform ties one
notification to one job.

The batch is keyed by `(groupId, isAllowedOverMetered)`, not by `groupId` alone.
The flag becomes the job's `setRequiredNetwork` constraint and a job carries
exactly one, so a group mixing "cellular is fine" with "Wi-Fi only" needs one job
per answer — otherwise one of the two is betrayed. That is at most two jobs per
group.

Three consequences are handled rather than inherited:

- `enqueue` is refused while the app is not visible, which a probe against
  Android 16 confirmed. A refusal falls through to a job for that download alone.
- A cancelled download reports to no listener (`ResumableDownloader.cancel` is
  explicit about it), so cancelling one item retires its work item by hand. An
  item cannot be pulled out of the system's queue, so one cancelled before its
  turn is marked and skipped when it is dequeued.
- A batch job's extras name the group, not the files, and the work queue cannot
  be read back, so `getExistingDownloads` would miss everything still queued.
  Each enqueue records what it is downloading, and the records are dropped with
  their batch job.

The batch notification stands for the whole group, so its Cancel button cancels
every download under it — running or still queued — through the same path as
`task.stop()`, then tears the job down. `CancelDownloadReceiver` takes
`EXTRA_GROUP_ID` for that, alongside the per-download `EXTRA_CONFIG_ID`.

The aggregate progress goes on with `JOB_END_NOTIFICATION_POLICY_REMOVE` rather
than `DETACH`. DETACH exists for the case where several jobs share one id and an
early finisher must not take it down; a batch is one job, so detaching outlived
it — the last policy set wins, and a progress update racing after the final file's
REMOVE left the notification posted for good, frozen on its last text.

Measured on a Samsung SM-A566B (Android 16) with `example/`, five downloads at
once: upstream takes five job slots and posts seven notifications; this branch
takes one slot and posts one, and the shade is empty once the batch ends. With
`summaryOnly` off, both take five slots and post five notifications plus a
summary.

## Build changes

`packageManager` is set to the monorepo's pnpm pin rather than yarn. Left at
yarn, corepack hands the install to a package manager the workspace does not
use and `pnpm install` fails before it can link anything.

`lib/` is built, not committed: `main` points at `lib/index.js`, produced by
`prepare` → `build` → `tsc -p tsconfig.build.json`. pnpm runs `prepare` on
install, but an up-to-date install skips it — which is why
`.gitlab/workspace/install.sh` rebuilds the output when it is missing. Metro is
unaffected either way, since `react-native` points at `src/index.ts`.

## Rebasing onto a newer upstream release

```sh
git fetch upstream
git rebase upstream/main kraaft/v4
```

The patches touch two Android files only; the JS side is untouched, so a
rebase conflicts only where upstream has reworked the UIDT notification code.
If upstream adopts the shared-notification approach, drop the first patch.

