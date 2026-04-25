package com.grid07.service;

import com.grid07.config.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/*
 * Handles notification throttling so users don't get spammed.
 *
 * Logic:
 * - When a bot interacts with a user's post, check if we already sent
 *   a notification to that user in the last 15 minutes.
 * - If we haven't: send immediately and set a 15 min cooldown key in redis
 * - If we have: add the notification to a redis list (acts like a queue)
 *
 * The CRON sweeper in NotificationScheduler reads these queues every 5 minutes
 * and sends a single summarized notification instead of many individual ones.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final StringRedisTemplate redis;

    @Value("${app.redis.notif-cooldown-ttl}")
    private long notifCooldownTtl;

    public void handleBotInteraction(Long userId, String botName, Long postId) {
        String notifMessage = "Bot '" + botName + "' replied to your post #" + postId;
        String cooldownKey = RedisKeys.notifCooldown(userId);

        // check if user already got a notification recently
        Boolean firstNotif = redis.opsForValue().setIfAbsent(
            cooldownKey,
            "sent",
            Duration.ofSeconds(notifCooldownTtl)
        );

        if (Boolean.TRUE.equals(firstNotif)) {
            // no cooldown active, send right away
            log.info("Push Notification Sent to User {}: {}", userId, notifMessage);
        } else {
            // cooldown is active, queue it for the batch sweep
            String listKey = RedisKeys.pendingNotifs(userId);
            redis.opsForList().leftPush(listKey, notifMessage);
            log.debug("notification queued for user {} (in cooldown window)", userId);
        }
    }

    // reads all pending notifications for a user and clears the list
    public List<String> drainPendingNotifications(Long userId) {
        String listKey = RedisKeys.pendingNotifs(userId);

        // get everything in the list
        List<String> pending = redis.opsForList().range(listKey, 0, -1);

        if (pending == null || pending.isEmpty()) {
            return new ArrayList<>();
        }

        // delete the list so we don't process these again
        redis.delete(listKey);

        return pending;
    }

    // finds all users who currently have queued notifications
    public Set<String> findUsersWithPendingNotifications() {
        // using keys() here - note: this scans the whole keyspace which isn't ideal
        // for large prod systems but works fine for this scale
        // TODO: look into SCAN with cursor for better approach
        return redis.keys("user:*:pending_notifs");
    }
}
