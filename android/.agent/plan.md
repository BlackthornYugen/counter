# Project Plan

Multi-Counter App enhancement:
1. Remove refresh button and "reset counter" button.
2. Support pinned counters via a persistent foreground service / silent persistent notification.
3. When a user "Pins" a counter, it creates/updates a persistent notification for pinned counter(s).
4. The persistent notification allows quick increment (+) and decrement (-) actions directly from the notification shade without opening the full app.
5. Dismissing the notification unpins the counter. If no counters are pinned, the persistent notification automatically stops/goes away.
6. The persistent foreground service architecture lays the foundation for maintaining a constant websocket connection in future iterations.

## Project Brief

# Project Brief: Multi-Counter App

## Features
- **Multi-Counter Management**: Create, view, and control multiple counters with a simplified interface (legacy refresh and reset controls removed).
- **Pinned Counter Foreground Service**: Pin target counters to initiate a persistent foreground service that maintains continuous notification status.
- **Interactive Notification Controls**: Direct increment (+) and decrement (-) actions embedded within the notification shade to update counter values without opening the full app UI.
- **Automatic Service Lifecycle**: Dismissing a notification unpins the corresponding counter; the background foreground service automatically shuts down when no counters remain pinned.

## High-Level Tech Stack
- **Language**: Kotlin
- **UI Framework**: Jetpack Compose, Material Design 3
- **Navigation Strategy**: Jetpack Navigation 3 (state-driven)
- **Adaptive UI Strategy**: Compose Material Adaptive library (`androidx.compose.material3.adaptive`)
- **Concurrency & Async Operations**: Kotlin Coroutines & Flow
- **System Components**: Foreground Service, `NotificationManager`, and PendingIntents / BroadcastReceivers for action callbacks
- **Architecture**: ViewModel and StateFlow for state management

## Implementation Steps
**Total Duration:** 52m 7s

### Task_1_DataAndNetworkSetup: Implement counter data model, local storage/repository for counter management (CRUD operations), and Ktor client integration for counter.jskw.dev API publishing/syncing.
- **Status:** COMPLETED
- **Updates:** Successfully created Counter model with CRUD local storage using Room, Ktor network client for counter.jskw.dev API, CounterRepositoryImpl with remote publishing logic and UUID handling (viewUuid, updateUuid, deleteUuid), and unit tests. Build and unit tests verified passing.
- **Acceptance Criteria:**
  - Counter model and repository created with support for create, increment, decrement, reset, delete
  - Ktor HTTP client setup for counter.jskw.dev API publishing and updating
- **Duration:** 6m 54s

### Task_2_UIAndViewModel: Implement ViewModel and Jetpack Compose UI with Material 3 including ListDetailPaneScaffold adaptive layout, counter controls, and publish/share toggle.
- **Status:** COMPLETED
- **Updates:** Successfully implemented CounterViewModel, AddCounterDialog with preset suggestions, CounterListPane, CounterDetailPane with touch targets for count controls, publish/share button with system share sheet integration, and ListDetailPaneScaffold adaptive layout in CounterApp. MainActivity integrated. Unit tests passing and build verified.
- **Acceptance Criteria:**
  - ViewModel manages counter state and API sync operations
  - Compose UI displays list of counters, counter details, increment/decrement/reset/delete controls, and share/publish button
  - Adaptive layout implemented for responsive screen support
- **Duration:** 2m 57s

### Task_3_RunAndVerify: Run the app, verify stability and functionality. Instruct critic_agent to verify application stability (no crashes), confirm alignment with user requirements, and report critical UI issues.
- **Status:** COMPLETED
- **Updates:** Verified by critic_agent on emulator. Application is stable with zero crashes. All counter features verified working: creating counters via suggestion chips and custom title, increment/decrement/reset, publishing/sharing live counts with counter.jskw.dev UUID generation, copying/sharing links, and deleting counters. Material 3 UI is clean and edge-to-edge.
- **Acceptance Criteria:**
  - build pass
  - app does not crash
  - make sure all existing tests pass
  - All counter features verified working seamlessly
- **Duration:** 2m 51s

### Task_4_PinnedCounterServiceAndUIUpdates: Update Counter model/Room schema with isPinned field, remove refresh/reset buttons, implement PinnedCounterService with interactive notifications (+/- actions and dismissal handler), add Pin/Unpin controls, and handle notification permissions.
- **Status:** COMPLETED
- **Updates:** Successfully added isPinned to Counter model and Room schema, removed Reset and Refresh buttons, added Pin/Unpin UI controls and POST_NOTIFICATIONS permission request, implemented PinnedCounterService foreground service with silent persistent notification featuring + / - quick action PendingIntents and DeleteIntent dismissal handler. Verified with assembleDebug and unit tests.
- **Acceptance Criteria:**
  - isPinned field added to Counter model and Room schema
  - Refresh and reset counter buttons removed from UI
  - Pin/Unpin UI buttons added with notification permission handling on Android 13+
  - PinnedCounterService foreground service built with persistent notification showing pinned counter status
  - Notification actions (+/-) update counter value and dismissal unpins counter
- **Duration:** 4m 12s

### Task_5_RunAndVerify: Run the app, verify stability and functionality of pinned counters, notification shade actions, UI changes, and foreground service lifecycle. Instruct critic_agent to verify application stability (no crashes), confirm alignment with user requirements, and report critical UI issues.
- **Status:** COMPLETED
- **Updates:** Verified on emulator by critic_agent. PinnedCounterService starts synchronously without crashes. Persistent silent notification appears when a counter is pinned, with real-time + / - notification actions updating counts across notification shade and app UI. Unpinning removes notification and stops service cleanly. Room schema version 3 running smoothly. Reset and Refresh buttons confirmed removed.
- **Acceptance Criteria:**
  - build pass
  - app does not crash
  - make sure all existing tests pass
  - Pinned counters, notification shade increment/decrement, and pin/unpin features verified working
- **Duration:** 35m 13s

