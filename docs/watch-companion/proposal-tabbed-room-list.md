# Proposal: Tabbed Room List with Favorites / All Rooms

## Goal

Replace the single scrollable room list on the main screen with a two-tab swipeable layout:
- **Tab 1 (Favorites):** Shows only favorited rooms
- **Tab 2 (All Rooms):** Shows all other rooms

If the user has no favorites, default to showing the All Rooms tab on app launch.

## Current State

- `FavoritesScreen` renders a single `ScalingLazyColumn` with two sections: "★ Favorites" header + favorite rooms, then "Recent Rooms" header + non-favorite rooms
- Data comes from `WearBridgeClient.favorites` StateFlow (`List<WatchFavoriteRoom>`)
- Each `WatchFavoriteRoom` has an `isFavorite: Boolean` field
- Infinite scroll is implemented: when nearing the bottom, requests more rooms from the phone via `WatchCommand.RefreshRooms`

## Wear OS UI Patterns for Tabs

### Available options

1. **`HorizontalPager` from standard Compose Foundation** — Available transitively in the wearapp module. Not Wear-native but works. Supports swipe gestures between pages and can show a page indicator.

2. **`SwipeDismissableNavHost` with two routes** — The current navigation host. Could add `favorites` and `allRooms` as sibling routes. However, swipe-to-dismiss in `SwipeDismissableNavHost` is meant for back navigation (right-to-left dismiss), not lateral tab switching.

3. **Custom `HorizontalPager` + `HorizontalPageIndicator`** from Wear Compose Foundation — `HorizontalPageIndicator` IS available in `androidx.wear.compose:compose-foundation:1.5.0`. Combined with the standard `HorizontalPager`, this gives a native Wear OS feel with the dot indicator at the bottom.

### Recommendation: `HorizontalPager` + `HorizontalPageIndicator`

This matches how Wear OS system apps (like the tile carousel and watch face complications) present swipeable content. The page indicator dots at the bottom are the standard Wear OS affordance for "there are more pages".

## Implementation Plan

### Step 1: Create `RoomListPagerScreen` composable

```kotlin
@Composable
fun RoomListPagerScreen(
    bridge: WearBridgeClient,
    onOpenRoom: (roomId: String) -> Unit,
) {
    val rooms by bridge.favorites.collectAsState()
    val favorites = rooms.filter { it.isFavorite }
    val otherRooms = rooms.filterNot { it.isFavorite }

    // Default to tab 1 (all rooms) if no favorites
    val initialPage = if (favorites.isEmpty()) 1 else 0
    val pagerState = rememberPagerState(initialPage = initialPage) { 2 }

    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(state = pagerState) { page ->
            when (page) {
                0 -> FavoritesListView(
                    rooms = favorites,
                    onOpenRoom = onOpenRoom,
                )
                1 -> AllRoomsListView(
                    rooms = otherRooms,
                    bridge = bridge,
                    onOpenRoom = onOpenRoom,
                )
            }
        }
        HorizontalPageIndicator(
            pageIndicatorState = ...,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
```

### Step 2: Extract list views from current `FavoritesScreen`

- `FavoritesListView` — `ScalingLazyColumn` with only favorite rooms, phone reachability indicator, empty state ("No favorites yet — star rooms in Element X")
- `AllRoomsListView` — `ScalingLazyColumn` with non-favorite rooms, infinite scroll pagination logic (moved from current `FavoritesScreen`)

### Step 3: Update navigation

In `WearMainActivity`, replace the `favorites` route with the new `RoomListPagerScreen`:

```kotlin
composable("favorites") {
    RoomListPagerScreen(
        bridge = bridgeClient,
        onOpenRoom = { roomId -> navController.navigate("room?roomId=$roomId") },
    )
}
```

### Step 4: Handle empty favorites gracefully

- If `favorites.isEmpty()`, set `initialPage = 1` so the pager starts on the All Rooms tab
- On the Favorites page, show an empty state: centered text "No favorites yet" with a subtitle "Star rooms on your phone"
- The pager still allows swiping to the (empty) favorites tab

### Step 5: Page indicator integration

Wear Compose Foundation's `HorizontalPageIndicator` needs a `PageIndicatorState`:

```kotlin
val pageIndicatorState = object : PageIndicatorState {
    override val pageCount: Int get() = 2
    override val pageOffset: Float get() = pagerState.currentPageOffsetFraction
    override val selectedPage: Int get() = pagerState.currentPage
}
```

## Files to Modify

| File | Change |
| --- | --- |
| `wearapp/.../favorites/FavoritesScreen.kt` | Refactor into `RoomListPagerScreen` + extract `FavoritesListView` and `AllRoomsListView` |
| `wearapp/.../ui/WearMainActivity.kt` | Update `favorites` route to use `RoomListPagerScreen` |
| `wearapp/build.gradle.kts` | No changes needed — `compose-foundation:1.5.0` already included |

## Visual Layout

```
┌─────────────────────┐    ┌─────────────────────┐
│    ★ Favorites       │    │    All Rooms          │
│                     │    │                       │
│  [Alice       💬 3] │    │  [#general     💬 12] │
│  [Bob         💬 1] │    │  [#random      💬 5]  │
│  [Carol       💬 7] │    │  [#dev-team    💬 2]  │
│                     │    │  [Dave         💬 1]  │
│                     │    │  [#announcements    ] │
│                     │    │                       │
│       ● ○           │    │       ○ ●             │
└─────────────────────┘    └─────────────────────┘
      Page 1                       Page 2
     (swipe →)                   (← swipe)
```

## Edge Cases

- **No favorites, no rooms:** Show single empty state "Open Element X on your phone to sync"
- **Only favorites, no other rooms:** Still show both tabs; All Rooms tab says "All rooms shown in Favorites"
- **Pager state persistence:** Use `rememberPagerState` — survives recomposition but resets on process death (acceptable for watch)
- **Infinite scroll:** Only applies to the All Rooms tab (Tab 2). Favorites are finite and controlled by the user.

## Effort

Small-medium. The core UI is straightforward — most work is refactoring the existing `FavoritesScreen` into two list views and wiring the pager. No new bridge protocol changes needed.
