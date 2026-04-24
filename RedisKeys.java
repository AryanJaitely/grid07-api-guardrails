package com.grid07.config;

// keeping all redis key strings in one place so i don't get typos everywhere
// TODO: maybe move TTL values here too later

public class RedisKeys {

    private RedisKeys() {}

    public static String viralityScore(Long postId) {
        return "post:" + postId + ":virality_score";
    }

    public static String botCount(Long postId) {
        return "post:" + postId + ":bot_count";
    }

    // this key is set with a 10 min TTL to block the same bot from spamming the same user
    public static String cooldown(Long botId, Long humanId) {
        return "cooldown:bot_" + botId + ":human_" + humanId;
    }

    public static String pendingNotifs(Long userId) {
        return "user:" + userId + ":pending_notifs";
    }

    public static String notifCooldown(Long userId) {
        return "notif_cooldown:user_" + userId;
    }
}
