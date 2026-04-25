package com.grid07.scheduler;

import com.grid07.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/*
 * Runs every 5 minutes (simulating a 15-min production sweep).
 * Finds all users with queued notifications and sends them a single
 * summarized message instead of many individual ones.
 *
 * This prevents spam - if 30 bots reply to your post, you get one
 * notification saying "Bot X and 29 others interacted with your posts"
 * instead of 30 separate pings.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationScheduler {

    private final NotificationService notificationService;

    @Scheduled(cron = "${app.scheduler.notif-sweep-cron}")
    public void sweepPendingNotifications() {
        log.info("--- notification sweep started ---");

        Set<String> keys = notificationService.findUsersWithPendingNotifications();

        if (keys == null || keys.isEmpty()) {
            log.info("no pending notifications found");
            return;
        }

        int usersNotified = 0;

        for (String key : keys) {
            // key format is "user:{id}:pending_notifs" - extract the id
            Long userId = extractUserId(key);
            if (userId == null) continue;

            List<String> pending = notificationService.drainPendingNotifications(userId);
            if (pending.isEmpty()) continue;

            String summary = buildSummaryMessage(pending);
            log.info("Summarized Push Notification: User {} <- {}", userId, summary);
            usersNotified++;
        }

        log.info("--- sweep done, notified {} user(s) ---", usersNotified);
    }

    private Long extractUserId(String key) {
        try {
            // split "user:42:pending_notifs" by ":" and take the middle part
            String[] parts = key.split(":");
            return Long.parseLong(parts[1]);
        } catch (Exception e) {
            log.warn("couldn't parse user id from key: {}", key);
            return null;
        }
    }

    private String buildSummaryMessage(List<String> notifications) {
        if (notifications.size() == 1) {
            return notifications.get(0);
        }

        // use the first one as the headline, rest become "N others"
        String first = notifications.get(0);
        int othersCount = notifications.size() - 1;

        if (othersCount == 1) {
            return first + " and 1 other interacted with your posts.";
        } else {
            return first + " and " + othersCount + " others interacted with your posts.";
        }
    }
}
