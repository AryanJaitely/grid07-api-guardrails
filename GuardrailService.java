package com.grid07.service;

import com.grid07.config.RedisKeys;
import com.grid07.dto.GuardrailException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/*
 * This service is responsible for the three Redis guardrails that prevent bots
 * from spamming posts.
 *
 * The tricky part is making these checks thread-safe. If 200 requests come in
 * at the same time and we just do a "read-then-check", all 200 will read the same
 * value and all 200 will pass the check - even if only 1 should be allowed.
 *
 * The fix is to use Redis atomic operations:
 *
 * For the horizontal cap (max 100 bot replies per post):
 *   Instead of reading the counter and then checking, we INCREMENT first and
 *   check the value we got back. Redis INCR is atomic - it's guaranteed that
 *   only one request gets any given number back. So if the counter returns 101,
 *   we know we went over and we decrement to undo it.
 *
 * For the cooldown (bot can't interact with same human twice in 10 mins):
 *   We use SET NX (set if not exists) with a TTL. This is also atomic in Redis.
 *   If the key already exists it means the bot is on cooldown. If it doesn't
 *   exist, we set it and allow the interaction. Redis will auto-delete the key
 *   after 10 minutes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuardrailService {

    private final StringRedisTemplate redis;

    @Value("${app.guardrails.max-bot-replies}")
    private int maxBotReplies;

    @Value("${app.guardrails.max-comment-depth}")
    private int maxCommentDepth;

    @Value("${app.redis.cooldown-ttl}")
    private long cooldownTtl;

    // ---- GUARDRAIL 1: Horizontal Cap ----------------------------------------
    // max 100 bot replies per post

    public void checkAndReserveHorizontalCap(Long postId) {
        String key = RedisKeys.botCount(postId);

        // increment first, then check - this is the atomic approach
        // if we checked first and then incremented, two requests could both
        // read 99, both pass the check, and both increment to 100 and 101
        Long newCount = redis.opsForValue().increment(key);

        if (newCount != null && newCount > maxBotReplies) {
            // went over the limit - undo the increment so the counter stays correct
            redis.opsForValue().decrement(key);
            log.warn("horizontal cap hit for post {}, current count: {}", postId, newCount - 1);
            throw new GuardrailException("This post has reached the limit of " + maxBotReplies + " bot replies.");
        }

        log.debug("horizontal cap ok for post {}: {}/{}", postId, newCount, maxBotReplies);
    }

    // called if the database write fails after we already incremented redis
    // so the redis counter doesn't get out of sync with actual db data
    public void releaseHorizontalCap(Long postId) {
        redis.opsForValue().decrement(RedisKeys.botCount(postId));
        log.debug("released horizontal cap slot for post {} due to db error", postId);
    }

    // ---- GUARDRAIL 2: Vertical Cap ------------------------------------------
    // comments can't go deeper than 20 levels

    public void checkVerticalCap(int depthLevel) {
        if (depthLevel > maxCommentDepth) {
            log.warn("vertical cap hit, depth {} exceeds max {}", depthLevel, maxCommentDepth);
            throw new GuardrailException("Thread is too deep. Max depth is " + maxCommentDepth + " levels.");
        }
    }

    // ---- GUARDRAIL 3: Cooldown Cap ------------------------------------------
    // a bot can only interact with the same human once every 10 minutes

    public void checkAndSetCooldown(Long botId, Long humanId) {
        String key = RedisKeys.cooldown(botId, humanId);
        Duration ttl = Duration.ofSeconds(cooldownTtl);

        // setIfAbsent = Redis SET NX PX
        // returns true if the key was set (first time in this window)
        // returns false if the key already existed (bot is on cooldown)
        Boolean wasSet = redis.opsForValue().setIfAbsent(key, "active", ttl);

        if (Boolean.FALSE.equals(wasSet)) {
            long minutes = cooldownTtl / 60;
            log.warn("cooldown cap hit: bot {} already interacted with user {} recently", botId, humanId);
            throw new GuardrailException("Bot " + botId + " needs to wait " + minutes + " minutes before interacting with this user again.");
        }

        log.debug("cooldown set for bot {} / user {}, expires in {}s", botId, humanId, cooldownTtl);
    }

    public long getBotCount(Long postId) {
        String raw = redis.opsForValue().get(RedisKeys.botCount(postId));
        if (raw == null) return 0;
        return Long.parseLong(raw);
    }
}
