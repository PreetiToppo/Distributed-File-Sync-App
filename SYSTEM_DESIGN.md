# System Design: Distributed File Sync App

> This document explains every architectural decision made in this project —
> the tradeoffs considered, the failure modes handled, and what would change at scale.
> Written to mirror the kind of thinking expected in a FAANG system design interview.

---

## Problem Statement

Build an Android app that syncs files to the cloud, where:
- The app must work **fully offline** and sync when connectivity returns
- Multiple devices can edit the **same file concurrently**
- Syncs must be **resumable** — a dropped connection shouldn't restart a full upload
- The system must never **silently lose data** during conflicts

---

## High-Level Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                        Android App                           │
│                                                              │
│  ┌──────────────┐    ┌─────────────────┐    ┌────────────┐  │
│  │  Compose UI  │───▶│   ViewModel     │───▶│  UseCases  │  │
│  │(FileBrowser) │    │(StateFlow/UDF)  │    │  (Domain)  │  │
│  └──────────────┘    └─────────────────┘    └─────┬──────┘  │
│                                                    │         │
│  ┌─────────────────────────────────────────────────▼──────┐  │
│  │                    Repository Layer                     │  │
│  │  ┌──────────────────────┐   ┌────────────────────────┐ │  │
│  │  │   Room DB (local)    │   │  Firebase (remote)     │ │  │
│  │  │  - sync_files table  │   │  - Firestore metadata  │ │  │
│  │  │  - sync_queue table  │   │  - Storage chunks      │ │  │
│  │  └──────────────────────┘   └────────────────────────┘ │  │
│  └────────────────────────────────────────────────────────┘  │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐    │
│  │              WorkManager (SyncWorker)                 │    │
│  │  Constraints: NETWORK_CONNECTED                       │    │
│  │  Backoff: EXPONENTIAL                                 │    │
│  │  Queue: persisted in Room (survives process death)    │    │
│  └──────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
```

---

## Clean Architecture Layers

The codebase follows strict Clean Architecture with three layers.
The key rule: **dependencies only point inward** — domain knows nothing about data or UI.

```
presentation/          ← Compose UI + ViewModel. Depends on domain only.
    FileBrowserScreen
    FileBrowserViewModel

domain/                ← Pure Kotlin. Zero Android imports. Fully unit-testable.
    model/
        SyncFile        ← Core domain entity
        VectorClock     ← Conflict detection logic lives here
    repository/
        FileRepository  ← Interface only. No implementation details.
    usecase/
        AddFileUseCase
        ConflictResolutionUseCase
        ResolveConflictUseCase

data/                  ← Implements domain interfaces. Knows about Room + Firebase.
    local/
        SyncDatabase    ← Room database
        SyncFileDao     ← File metadata queries
        SyncQueueDao    ← Sync queue (persistence layer for WorkManager)
    remote/
        FirebaseDataSource  ← All Firebase interactions isolated here
    repository/
        FileRepositoryImpl  ← Bridges local + remote data sources

sync/
    SyncWorker          ← WorkManager worker. Orchestrates upload/conflict pipeline.
```

**Why this separation matters at scale:**
- You can swap Firebase for AWS S3 by only changing `FirebaseDataSource` — nothing else changes
- Domain UseCases can be unit-tested with MockK without booting an emulator
- A new junior engineer can work on UI without touching sync logic

---

## Decision 1: Chunked Upload with SHA-256 Delta Diffing

### The problem
A naive implementation calls a single upload API with the whole file.
If the connection drops at 95%, the user restarts from 0%.
For large files (100MB+), this is unacceptable.

### The solution
Files are split into **4MB chunks**, each identified by its SHA-256 checksum.

```
File (12MB)
  ├── Chunk 0 (4MB) → SHA-256: "a1b2c3..."
  ├── Chunk 1 (4MB) → SHA-256: "d4e5f6..."
  └── Chunk 2 (4MB) → SHA-256: "g7h8i9..."
```

Before uploading a chunk, the client checks if that chunk's checksum already exists in Firebase Storage.
If it does, it skips that chunk entirely. This gives us:

1. **Resumable uploads** — restart from the last failed chunk, not the beginning
2. **Deduplication** — if two files share identical blocks, the second upload is near-instant
3. **Integrity verification** — if a chunk's remote checksum doesn't match local, re-upload it

### Chunk size tradeoff
- **Too small (256KB):** Too many HTTP requests, high overhead
- **Too large (50MB):** Failure recovery still expensive, OOM risk on low-RAM devices
- **4MB:** Matches Firebase Storage's internal multipart boundaries; sweet spot for mobile

---

## Decision 2: Vector Clocks Instead of Last-Write-Wins

### Why Last-Write-Wins (LWW) is dangerous
LWW uses wall clock timestamps to decide which version to keep.

```
Device A edits at 10:00:01 AM → timestamp = T1
Device B edits at 10:00:02 AM → timestamp = T2

LWW decision: keep B's version (newer timestamp)
Result: A's edit is silently discarded ← DATA LOSS
```

Mobile clocks can drift by seconds. Users can have clock skew.
LWW silently loses data without telling anyone.

### Vector clocks detect true conflicts

Each device maintains a counter for every device it has seen:

```
Initial state:
  Device A: {}
  Device B: {}

A edits the file:
  Device A clock: { "A": 1 }

B syncs from A, then edits:
  Device B clock: { "A": 1, "B": 1 }   ← B knows about A's edit

B syncs to server. A edits again WITHOUT syncing first:
  Device A clock: { "A": 2 }
  Device B clock: { "A": 1, "B": 1 }

Compare:
  A["A"]=2 > B["A"]=1 → A is ahead in its own dimension
  A["B"]=0 < B["B"]=1 → B is ahead in its own dimension
  → CONCURRENT → CONFLICT detected ← surface to user
```

The four possible outcomes:
| Relation   | Meaning                        | Action            |
|------------|--------------------------------|-------------------|
| AFTER      | Local is newer                 | Upload local      |
| BEFORE     | Remote is newer                | Download remote   |
| EQUAL      | Identical                      | No-op             |
| CONCURRENT | Both edited independently      | Ask the user      |

### Why this matters in interviews
When asked "how do you handle conflicts in a distributed system?",
most candidates say "last-write-wins." Vector clocks show you understand
*causality* — not just timestamps. This is the same technique used by
Amazon DynamoDB, Riak, and Cassandra.

---

## Decision 3: Room-Persisted Sync Queue + WorkManager

### The problem
If the user adds a file to sync, the app process can be killed before the upload completes.
An in-memory queue (a List in the ViewModel) is lost on process death.

### The solution
Every sync operation is written to the `sync_queue` table in Room **before** any network call.

```
User adds file
    ↓
AddFileUseCase
    ↓
INSERT INTO sync_files (status = PENDING_UPLOAD)    ← persisted
INSERT INTO sync_queue (fileId, operation = UPLOAD) ← persisted
    ↓
WorkManager.enqueue(SyncWorker, constraints = NETWORK)
```

WorkManager reads from the Room queue when it wakes up.
Even if the device reboots, WorkManager re-schedules pending work on boot.
The queue is the source of truth — WorkManager is just the executor.

**Exponential backoff:**
On failure, WorkManager waits 10s → 20s → 40s → ... before retrying.
After 3 retries, the item is marked `ERROR` and the user is notified.
This prevents hammering Firebase when the server is down.

---

## Decision 4: Offline-First with Room as Source of Truth

The UI **never reads directly from Firebase**.
All reads go through Room. Firebase is write-only from the UI's perspective.

```
Firebase change detected (Firestore listener)
    ↓
Write to Room DB
    ↓
Room emits Flow update
    ↓
ViewModel collects
    ↓
UI recomposes
```

This means:
- The app works identically offline and online (no loading spinners for reads)
- There's one source of truth (Room), not two (Room + Firestore)
- Firestore's offline SDK + Room together give double-layer caching

---

## What I'd Do Differently at Scale

| Current (MVP)                     | At Scale (10M users)                          |
|-----------------------------------|-----------------------------------------------|
| Firebase Storage for chunks       | S3 + CloudFront CDN for global edge delivery  |
| Firestore for metadata            | DynamoDB with GSIs for complex queries        |
| Single Room DB                    | Encrypted Room DB (SQLCipher) for enterprise  |
| No compression                    | zstd compression before chunking              |
| Vector clocks stored as JSON      | Protobuf serialization for smaller payloads   |
| Manual conflict UI                | CRDT-based auto-merge for text files          |
| WorkManager periodic sync         | FCM push notifications to trigger sync        |
| No rate limiting                  | Token bucket rate limiter per device          |

---

## Performance Characteristics

| Operation          | Complexity | Notes                                      |
|--------------------|------------|--------------------------------------------|
| Observe files      | O(1)       | Room Flow — reactive, no polling           |
| Conflict detection | O(D)       | D = number of devices that touched the file|
| Clock merge        | O(D)       | One pass over all device entries           |
| Chunk upload       | O(N/4MB)   | N = file size, parallelizable              |
| Queue lookup       | O(1)       | Primary key lookup on sync_queue           |

---

## Running the Project

See [README.md](README.md) for setup instructions.
