# Grid07 Backend Intern Assignment

Spring Boot microservice that handles posts, comments, and bot interactions with Redis-backed guardrails to prevent bot spam.

---

## Tech Stack

- Java 17
- Spring Boot 3.2
- PostgreSQL 15
- Redis 7
- Docker + Docker Compose

---

## Project Structure

```
src/main/java/com/grid07/
├── ApiGuardrailsApplication.java
├── config/
│   ├── RedisConfig.java          # redis bean setup
│   └── RedisKeys.java            # all redis key strings in one place
├── entity/
│   ├── User.java
│   ├── Bot.java
│   ├── Post.java
│   └── Comment.java
├── repository/                   # spring data jpa repos
├── dto/
│   ├── CreatePostRequest.java
│   ├── AddCommentRequest.java
│   ├── LikePostRequest.java
│   ├── ApiResponse.java
│   └── GuardrailException.java
├── service/
│   ├── PostService.java          # main business logic
│   ├── GuardrailService.java     # the three redis locks
│   ├── ViralityService.java      # real-time score tracking
│   └── NotificationService.java  # throttle + queue logic
├── scheduler/
│   └── NotificationScheduler.java  # cron sweeper
└── controller/
    ├── PostController.java
    ├── SetupController.java      # helper endpoints for seeding test data
    └── GlobalExceptionHandler.java
```

---

## How to Run

### 1. Start PostgreSQL and Redis

```bash
docker-compose up -d
```

This starts postgres on port 5432 and redis on port 6379. Hibernate will auto-create the tables on first run.

### 2. Start the app

```bash
./mvnw spring-boot:run
```

App runs on `http://localhost:8080`

### 3. Seed some test data

First create a user and a bot using the setup endpoints (see API section below), then use those IDs in your requests.

---

## API Endpoints

### Setup (for creating test data)

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/setup/users` | Create a user |
| POST | `/api/setup/bots` | Create a bot |
| GET | `/api/setup/users` | List all users |
| GET | `/api/setup/bots` | List all bots |

**Create user:**
```json
POST /api/setup/users
{
  "username": "alice",
  "isPremium": true
}
```

**Create bot:**
```json
POST /api/setup/bots
{
  "name": "Aria",
  "personaDescription": "A helpful assistant bot"
}
```

---

### Phase 1 — Core Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/posts` | Create a post |
| POST | `/api/posts/{postId}/comments` | Add a comment |
| POST | `/api/posts/{postId}/like` | Like a post |
| GET | `/api/posts/{postId}/virality` | Get virality score |

**Create post:**
```json
POST /api/posts
{
  "content": "hello world",
  "authorUserId": 1
}
```

**Add a human comment:**
```json
POST /api/posts/1/comments
{
  "content": "nice post!",
  "authorUserId": 1,
  "depthLevel": 0
}
```

**Add a bot comment:**
```json
POST /api/posts/1/comments
{
  "content": "interesting!",
  "authorBotId": 1,
  "depthLevel": 0,
  "targetHumanId": 1
}
```

**Like a post:**
```json
POST /api/posts/1/like
{
  "userId": 1
}
```

---

## Phase 2 — How the Redis Guardrails Work

This is the core of the assignment. There are three guardrails, all enforced using Redis atomic operations before any database write happens.

### Guardrail 1: Horizontal Cap (max 100 bot replies per post)

The tricky part here is concurrency. If 200 requests come in at the same time and we just read the counter and check it, all 200 will read the same value (say 99), all 200 will pass the check, and we end up with 200 comments instead of 100.

The fix is to **increment first, then check**:

```java
Long newCount = redis.opsForValue().increment(key);  // atomic INCR

if (newCount > 100) {
    redis.opsForValue().decrement(key);  // undo the increment
    throw new GuardrailException("post has hit the 100 bot reply limit");
}
```

Redis `INCR` is atomic — it increments and returns the new value as a single operation. No two requests can get the same number back. So if 200 requests hit this simultaneously, they'll get back 1, 2, 3... 100, 101, 102... and anything that gets back > 100 self-decrements and gets rejected. Exactly 100 go through, every time.

### Guardrail 2: Vertical Cap (max depth 20)

Simple check — if the `depthLevel` in the request is greater than 20, reject with 429. No Redis write needed here, it's just a comparison.

```java
if (depthLevel > maxCommentDepth) {
    throw new GuardrailException("thread is too deep");
}
```

### Guardrail 3: Cooldown Cap (bot can't interact with same human twice in 10 min)

Uses a Redis key with a TTL as a time-based lock:

```java
Boolean wasSet = redis.opsForValue().setIfAbsent(key, "active", Duration.ofSeconds(600));

if (Boolean.FALSE.equals(wasSet)) {
    throw new GuardrailException("bot needs to wait 10 minutes");
}
```

`setIfAbsent` maps to Redis `SET NX PX` — it only sets the key if it doesn't already exist, and it's atomic. First request sets the key and gets `true` (allowed). Any request within 10 minutes finds the key there and gets `false` (blocked). After 10 minutes Redis auto-deletes the key and the cycle resets.

### Why Redis instead of a database lock?

Database-level locking would technically work, but it holds a DB connection open while waiting. Under high concurrency this becomes a bottleneck quickly. Redis commands are atomic at the server level without locking, and Redis is significantly faster for this kind of counter/flag work.

### Why check guardrails before writing to PostgreSQL?

If Redis rejects the request we never open a DB transaction at all. This keeps the database load low and avoids needing to roll back writes.

---

## Phase 3 — Notification Engine

### Throttling

When a bot interacts with a user's post:

- Check if `notif_cooldown:user_{id}` exists in Redis
- **Doesn't exist** → send the push notification immediately, set the key with 15-min TTL
- **Exists** → push notification text into a Redis List (`user:{id}:pending_notifs`) for later

### CRON Sweeper

A `@Scheduled` task runs every 5 minutes. It:

1. Scans Redis for keys matching `user:*:pending_notifs`
2. For each user, reads and clears their notification list
3. Logs a summarized message:

```
Summarized Push Notification: User 1 <- Bot 'Aria' replied to your post #5 and 3 others interacted with your posts.
```

This way a user who gets 50 bot replies in 2 minutes receives one notification instead of 50.

---

## Redis Keys Used

| Key | Type | TTL | Purpose |
|-----|------|-----|---------|
| `post:{id}:virality_score` | String | none | running virality score |
| `post:{id}:bot_count` | String | none | number of bot replies |
| `cooldown:bot_{id}:human_{id}` | String | 600s | bot-human interaction cooldown |
| `user:{id}:pending_notifs` | List | none | queued notification strings |
| `notif_cooldown:user_{id}` | String | 900s | notification throttle |

---

## Running the Concurrency Test

The `ConcurrencyTest` fires 200 simultaneous requests and asserts exactly 100 succeed:

```bash
# update POST_ID and BOT_ID in the test file first
mvn test -Dtest=ConcurrencyTest
```

---

## Postman Collection

Import `Grid07_Postman_Collection.json` into Postman. Set the `userId`, `botId`, and `postId` collection variables after seeding your data.

---

## Known Limitations / Things I'd improve with more time

- `SetupController` has no input validation — if you send bad data it'll throw a 500 instead of a proper 400
- `NotificationService.findUsersWithPendingNotifications()` uses `redis.keys()` which scans the full keyspace — fine for this scale, would switch to SCAN with a cursor for production
- Likes aren't stored in PostgreSQL, only the virality score is updated in Redis — a real app would probably want a likes table for analytics
