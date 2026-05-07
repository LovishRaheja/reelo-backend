# reelo-backend

Ktor backend for Reelo — AI podcast micro-clip generator.

## Stack
- **Ktor** — HTTP server
- **Exposed** — Postgres ORM  
- **Supabase** — Postgres database
- **Cloudflare R2** — video storage + CDN
- **Cloudflare Workers AI** — Whisper transcription
- **Redis (Upstash)** — job queue
- **FFmpeg** — audio extraction + clip cutting

---

## Prerequisites

- JDK 17 or 21 — [Amazon Corretto](https://aws.amazon.com/corretto/)
- FFmpeg installed and on PATH
  - Mac: `brew install ffmpeg`
  - Ubuntu: `apt install ffmpeg`
  - Windows: download from ffmpeg.org, add to PATH
- A Supabase project (free tier is fine)
- A Cloudflare account with R2 enabled
- An Upstash Redis database (free tier)

---

## Setup

### 1. Clone and open in IntelliJ IDEA

Open IntelliJ → File → Open → select the `reelo-backend` folder.
IntelliJ will detect the Gradle project and sync automatically.

### 2. Configure environment variables

```bash
cp .env.example .env
```

Fill in `.env` with your credentials:

```
DATABASE_URL         → Supabase: Settings → Database → Connection string (URI)
SUPABASE_JWT_SECRET  → Supabase: Settings → API → JWT Secret
SUPABASE_JWT_ISSUER  → https://[your-project].supabase.co/auth/v1
SUPABASE_JWT_AUDIENCE → authenticated

CF_ACCOUNT_ID        → Cloudflare dashboard → top right account ID
R2_ACCESS_KEY_ID     → R2 → Manage R2 API Tokens → Create Token
R2_SECRET_ACCESS_KEY → same token creation step
R2_BUCKET            → name of your R2 bucket (create one called "reelo-videos")
R2_PUBLIC_URL        → R2 bucket → Settings → Public URL (enable public access)

CF_AI_API_TOKEN      → Cloudflare → AI → API Tokens → Create Token
REDIS_URL            → Upstash → your database → Redis URL
```

### 3. Load env vars (Mac/Linux)

```bash
export $(cat .env | grep -v '#' | xargs)
```

Or use IntelliJ's Run Configuration → Environment Variables to paste them in.

---

## Running locally

You need two terminal windows — one for the server, one for the worker.

### Terminal 1 — API server

```bash
./gradlew run
```

Server starts on http://localhost:8080

Test it:
```bash
curl http://localhost:8080/health
# → {"status":"ok","service":"reelo-backend"}
```

### Terminal 2 — Job worker

```bash
./gradlew run --args="worker"
```

Worker starts and watches Redis for jobs. You'll see:
```
Worker started, watching Redis queue...
```

---

## Building a fat JAR (for Railway)

```bash
./gradlew buildFatJar
```

Output: `build/libs/reelo-backend.jar`

Run it:
```bash
java -jar build/libs/reelo-backend.jar         # server
java -jar build/libs/reelo-backend.jar worker  # worker
```

---

## API endpoints

All endpoints are prefixed with `/api/v1`.

### Upload signing
```
POST /api/v1/uploads/sign
Body: { "fileName": "episode.mp3", "fileSize": 50000000, "contentType": "audio/mpeg", "sessionToken": "uuid" }
Response: { "uploadUrl": "https://...", "fileKey": "raw/session/job.mp3" }
```

### Create job
```
POST /api/v1/jobs
Body: { "fileKey": "raw/...", "originalFilename": "episode.mp3", "sessionToken": "uuid", "clipCount": 6 }
Response: { "id": "uuid", "sessionToken": "uuid", "status": "queued", "progress": 0 }
```

### Poll job status
```
GET /api/v1/jobs/{id}?session={token}
Response: { "id": "uuid", "status": "transcribing", "currentStep": "Listening to your episode...", "progress": 25 }
          when done: includes "episode" with all clips
```

### Get episode
```
GET /api/v1/episodes/{id}?session={token}
Response: { "id": "uuid", "clips": [...], "originalFilename": "...", ... }
```

### Download clip
```
GET /api/v1/clips/{id}/download?session={token}
→ 302 redirect to Cloudflare CDN URL
```

---

## Job status flow

```
queued       → job created, waiting for worker
downloading  → worker fetching video from R2
transcribing → Whisper running
analyzing    → audio energy map + picking clip windows
clipping     → FFmpeg cutting clips (updated per clip)
uploading    → clips going to R2
done         → all clips ready, episode populated in response
failed       → something went wrong, check errorCode
```

---

## Deploying to Railway

1. Push this repo to GitHub
2. Create a new Railway project → Deploy from GitHub repo
3. Add all env vars from `.env` in Railway → Variables
4. Railway auto-detects `railway.json` and builds the fat JAR
5. Add a second Railway service (same repo) with start command:
   `java -jar build/libs/reelo-backend.jar worker`
6. Add the same env vars to the worker service

Both services share the same Postgres, Redis, and R2.

---

## Project structure

```
src/main/kotlin/com/reelo/
├── Application.kt          ← entry point
├── di/AppModule.kt         ← Koin dependency wiring
├── models/Models.kt        ← request/response data classes
├── db/
│   ├── DatabaseFactory.kt  ← connection pool + schema init
│   ├── tables/Tables.kt    ← Exposed table definitions
│   └── repositories/
│       ├── JobRepository.kt
│       └── ClipRepository.kt
├── services/
│   ├── R2Service.kt        ← Cloudflare R2 upload/download/sign
│   ├── WhisperService.kt   ← Cloudflare AI transcription
│   └── FfmpegService.kt    ← audio extract, energy map, clip cut
├── worker/
│   ├── RedisQueue.kt       ← push/pop job IDs
│   └── JobProcessor.kt     ← full pipeline
└── plugins/
    ├── Plugins.kt          ← serialization, CORS, auth, errors
    └── Routing.kt          ← registers all routes
```
