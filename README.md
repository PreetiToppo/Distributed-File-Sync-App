# 📁 Distributed File Sync App

An offline-first Android app that syncs files to Firebase with **chunked resumable uploads**, **vector clock conflict resolution**, and a **Room-persisted sync queue** backed by WorkManager.

Built to demonstrate production-grade Android architecture for system design interviews.

---

## ✨ Features

| Feature | Implementation |
|---|---|
| Offline-first | Room DB as single source of truth; syncs when connectivity returns |
| Resumable uploads | Files split into 4MB chunks with SHA-256 checksums; failed uploads restart from last chunk |
| Conflict detection | Vector clocks compare causal history across devices — detects concurrent edits LWW misses |
| Crash-safe sync queue | Sync operations persisted in Room before network call; survives process death and reboots |
| Clean Architecture | data / domain / presentation layers; domain is pure Kotlin, zero Android imports |

---

## 🏗️ Architecture

```
UI (Jetpack Compose)
    └── FileBrowserViewModel (StateFlow / UDF)
            └── UseCases (pure Kotlin, unit-tested)
                    └── FileRepository (interface)
                            ├── FileRepositoryImpl
                            │       ├── Room DB  (local: SyncFileDao, SyncQueueDao)
                            │       └── FirebaseDataSource (remote: Firestore + Storage)
                            └── SyncWorker (WorkManager, Hilt-injected)
```

**Full architecture deep-dive → [SYSTEM_DESIGN.md](SYSTEM_DESIGN.md)**

---

## 🔑 Key Design Decisions

### 1. Vector Clocks (not Last-Write-Wins)
Last-write-wins silently discards data when two devices edit concurrently.
Vector clocks detect *causality* — if neither clock dominates, it's a true conflict
and the user is asked to decide. Same technique used by DynamoDB and Cassandra.

```kotlin
val relation = localFile.vectorClock.compare(remoteFile.vectorClock)
// AFTER     → local wins, upload
// BEFORE    → remote wins, download
// CONCURRENT → conflict, surface to user
```

### 2. Chunked Upload with SHA-256 Delta Diffing
Files are split into 4MB chunks. Each chunk is identified by its SHA-256 hash.
Already-uploaded chunks are skipped — uploads are resumable and deduplicated.

### 3. Room-Persisted Sync Queue
Sync operations are written to `sync_queue` in Room *before* any network call.
WorkManager reads this queue on startup — even after a crash or reboot.

---

## 🛠️ Tech Stack

| Layer | Technology |
|---|---|
| UI | Jetpack Compose, Material 3 |
| Architecture | MVVM, Clean Architecture, UDF |
| Local DB | Room (+ TypeConverters for VectorClock) |
| Background sync | WorkManager (CoroutineWorker, exponential backoff) |
| Remote storage | Firebase Storage (chunked), Firestore (metadata) |
| DI | Hilt |
| Async | Kotlin Coroutines + Flow |
| Serialization | kotlinx.serialization |
| Testing | JUnit 4, MockK, Turbine, kotlinx-coroutines-test |

---

## 🚀 Getting Started

### Prerequisites
- Android Studio Hedgehog or later
- JDK 17
- A Firebase project with **Firestore** and **Firebase Storage** enabled

### Setup

1. **Clone the repo**
   ```bash
   git clone https://github.com/PreetiToppo/DistributedFileSyncApp.git
   cd DistributedFileSyncApp
   ```

2. **Add your Firebase config**
   - Go to [Firebase Console](https://console.firebase.google.com) → Project Settings → Add Android app
   - Package name: `com.preetitoppo.filesync`
   - Download `google-services.json` and place it in `app/`

3. **Enable Firebase services**
   - Firestore Database → Create in test mode
   - Firebase Storage → Get started

4. **Build and run**
   ```bash
   ./gradlew assembleDebug
   ```

### Run unit tests
```bash
./gradlew test
```

---

## 🧪 Test Coverage

| Class | Tests |
|---|---|
| `VectorClock` | 9 tests — all four clock relations, merge, increment |
| `ConflictResolutionUseCase` | 5 tests — all resolution paths (KeepLocal, KeepRemote, NeedsUserDecision) |
| `ChecksumUtil` | 9 tests — SHA-256 consistency, chunk counts, ID generation |

---

## 📂 Project Structure

```
app/src/main/java/com/preetitoppo/filesync/
├── data/
│   ├── local/
│   │   ├── dao/          SyncFileDao, SyncQueueDao
│   │   ├── entity/       Room entities + SyncOperation enum
│   │   └── SyncDatabase.kt
│   ├── remote/
│   │   └── FirebaseDataSource.kt
│   └── repository/
│       └── FileRepositoryImpl.kt
├── di/
│   └── AppModule.kt      Hilt modules for DB, Firebase, WorkManager
├── domain/
│   ├── model/
│   │   └── SyncFile.kt   VectorClock, ClockRelation, ConflictResolution
│   ├── repository/
│   │   └── FileRepository.kt   (interface)
│   └── usecase/
│       └── SyncUseCases.kt     AddFile, ConflictResolution, ResolveConflict
├── sync/
│   └── SyncWorker.kt     WorkManager worker — orchestrates upload pipeline
├── ui/
│   ├── filebrowser/
│   │   ├── FileBrowserScreen.kt
│   │   └── FileBrowserViewModel.kt
│   └── theme/
│       └── Theme.kt
├── util/
│   └── ChecksumUtil.kt   SHA-256, chunk count, device ID
└── MainActivity.kt
```

---

## 📖 Further Reading

- [SYSTEM_DESIGN.md](SYSTEM_DESIGN.md) — Full architecture doc with tradeoffs
- [Vector Clocks — Why Logical Clocks are Easy](https://riak.com/posts/technical/vector-clocks-revisited/)
- [WorkManager Guide — Android Docs](https://developer.android.com/topic/libraries/architecture/workmanager)

---

## 📄 License

MIT License — see [LICENSE](LICENSE)
