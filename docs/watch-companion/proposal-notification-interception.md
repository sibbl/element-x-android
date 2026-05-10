# Proposal: Notification Interception & In-Conversation Quick Actions

## Goal

When the user taps a bridged notification from the main Element X phone app on the watch, open the corresponding message detail view in our Wear companion app. Additionally, enable quick actions (emoji reaction, text reply, voice reply) directly from the conversation timeline without needing to open message detail first.

## Current State

- **Phone notifications** use `NotificationCompat.Builder` with `MessagingStyle` (conversational style). Each notification carries: `sessionId`, `roomId`, `eventId`, `senderId`, `body`, `threadId`, `roomName`, etc. They include a `RemoteInput` quick-reply action and a mark-as-read action.
- **No `WearableExtender`** is used — notifications auto-bridge to Wear OS via the standard Android system bridge (system mirrors them).
- **Watch app** has zero notification code. Navigation is via `SwipeDismissableNavHost` with routes: `favorites`, `room?roomId=`, `message?roomId=&eventId=`, `thread?roomId=&rootId=`.
- **No deep links** are declared in the watch manifest — only `MAIN`/`LAUNCHER`.

## Approach Options

### Option A: Intercept bridged notifications with `NotificationListenerService`

A `NotificationListenerService` on the **watch** would listen for bridged notifications from the phone app (package `io.element.android.x.debug`). When the user taps, we replace the default tap action with a launch into our app's message detail route.

**Pros:**
- Full control over notification presentation on the watch
- Can extract `roomId`/`eventId` from notification extras
- Can add custom actions (react, reply) directly on the notification

**Cons:**
- Requires `BIND_NOTIFICATION_LISTENER_SERVICE` permission — user must grant in watch settings
- Fragile: depends on the phone app's notification structure (extras keys, group keys)
- Wear OS may not bridge all notification extras reliably

### Option B: Add `WearableExtender` on the phone side (Recommended)

Modify the phone app's `NotificationCreator` to add a `WearableExtender` with:
1. A custom action that launches our watch app with `roomId`/`eventId` extras
2. Watch-specific `RemoteInput` for quick reply
3. Custom emoji reaction actions

**Implementation:**
1. In `DefaultNotificationCreator.createMessagingStyleNotification()`, add:
   ```kotlin
   val wearLaunchIntent = Intent("io.element.android.wearapp.OPEN_MESSAGE")
       .setPackage("io.element.android.x.debug")
       .putExtra("roomId", roomId)
       .putExtra("eventId", eventId)

   NotificationCompat.WearableExtender()
       .setContentAction(0)
       .addAction(NotificationCompat.Action.Builder(...)
           .addRemoteInput(RemoteInput.Builder("reply").setLabel("Reply").build())
           .build())
   ```
2. On the watch `WearMainActivity`, parse intent extras in `onCreate()`:
   ```kotlin
   val roomId = intent.getStringExtra("roomId")
   val eventId = intent.getStringExtra("eventId")
   if (roomId != null && eventId != null) {
       navController.navigate("message?roomId=$roomId&eventId=$eventId")
   }
   ```

**Pros:**
- No extra permissions needed
- Clean integration with existing notification pipeline
- Phone controls what's shown on the watch

**Cons:**
- Requires modifying phone-side notification code (upstream touch)
- WearableExtender actions may be limited in what they can trigger

### Option C: Deep link via Wear Data Layer message

Phone sends a `MessageClient` message when a notification is posted. Watch side creates its own local notification with a deep-link `PendingIntent`.

**Pros:**
- Full control on both sides
- Can customize watch notification completely

**Cons:**
- Duplicates notifications (system bridge + our custom one)
- Need to suppress the bridged notification (requires `NotificationListenerService` anyway)
- More complex implementation

## Recommendation: Option B (WearableExtender)

Option B is the cleanest path. It leverages the existing notification pipeline, requires no extra permissions, and the phone app already has all the data needed.

## Implementation Plan

### Phase 1: Deep link from notification tap

1. **Phone side** — modify `DefaultNotificationCreator` (or our own wrapper in the watch bridge):
   - Add `WearableExtender` with a launch action targeting our watch app
   - Include `roomId` and `eventId` as extras

2. **Watch side** — modify `WearMainActivity.onCreate()`:
   - Check for `roomId`/`eventId` intent extras
   - Navigate to `message?roomId=X&eventId=Y` route
   - Handle `onNewIntent` for when activity is already running

3. **Watch manifest** — add intent filter for the custom action

### Phase 2: Quick actions in conversation timeline

4. **Add inline action row to `TimelineMessageRow`**:
   - Show a compact row of quick actions below each message (or on swipe/long-press):
     - React (👍 quick-react or opens emoji picker)
     - Reply (launches dictation)
     - Voice reply (starts voice recording)
   - These actions are already wired in `MessageDetailView` — extract and reuse the callbacks

5. **Add swipe-to-react gesture** (optional):
   - Swipe right on a message to quick-react with 👍
   - Uses existing `WatchCommand.SendReaction` bridge command

### Phase 3: Reply from notification

6. **Add `RemoteInput` to WearableExtender actions**:
   - User can reply directly from the notification without opening the app
   - Watch-side `BroadcastReceiver` captures the `RemoteInput` result
   - Sends `WatchCommand.SendText` via the bridge

## Files to Modify

| File | Change |
|---|---|
| `libraries/push/impl/.../NotificationCreator.kt` | Add `WearableExtender` with launch action + extras |
| `wearapp/src/main/AndroidManifest.xml` | Add intent filter for deep link action |
| `wearapp/.../WearMainActivity.kt` | Parse intent extras, navigate to message detail |
| `wearapp/.../TimelineItemView.kt` | Add inline quick action row |
| `wearapp/.../RoomScreen.kt` | Wire new quick actions to bridge commands |

## Effort Estimate

- Phase 1 (notification deep link): Small — ~2 files phone-side, ~2 files watch-side
- Phase 2 (inline quick actions): Medium — UI work in timeline row, reuse existing bridge commands
- Phase 3 (notification reply): Medium — new BroadcastReceiver + RemoteInput wiring

## Risks

- `WearableExtender` behavior varies across Wear OS versions and OEMs
- System notification bridging can be unreliable on some devices
- Users may need to ensure "Mirror phone notifications" is enabled in Wear OS settings
