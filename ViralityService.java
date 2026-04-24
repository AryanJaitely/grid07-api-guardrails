package com.grid07.service;

import com.grid07.config.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/*
 * Updates the virality score of a post in Redis whenever someone interacts with it.
 *
 * Scores:
 *   bot reply      -> +1
 *   human like     -> +20
 *   human comment  -> +50
 *
 * Storing this in Redis instead of postgres because it gets updated constantly
 * and we don't need to persist every single increment to the DB right away.
 * Redis INCRBY is atomic so concurrent updates don't overwrite each other.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ViralityService {

    private final StringRedisTemplate redis;

    public void onBotReply(Long postId) {
        String key = RedisKeys.viralityScore(postId);
        Long newScore = redis.opsForValue().increment(key, 1);
        log.debug("bot reply on post {} -> virality now {}", postId, newScore);
    }

    public void onHumanLike(Long postId) {
        String key = RedisKeys.viralityScore(postId);
        Long newScore = redis.opsForValue().increment(key, 20);
        log.debug("human like on post {} -> virality now {}", postId, newScore);
    }

    public void onHumanComment(Long postId) {
        String key = RedisKeys.viralityScore(postId);
        Long newScore = redis.opsForValue().increment(key, 50);
        log.debug("human comment on post {} -> virality now {}", postId, newScore);
    }

    public long getScore(Long postId) {
        String key = RedisKeys.viralityScore(postId);
        String raw = redis.opsForValue().get(key);

        // if no interactions yet the key won't exist in redis
        if (raw == null) {
            return 0;
        }

        return Long.parseLong(raw);
    }
}
