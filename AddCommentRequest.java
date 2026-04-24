package com.grid07.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AddCommentRequest {

    @NotBlank(message = "content cannot be empty")
    private String content;

    // one of these should be set depending on whether human or bot is commenting
    private Long authorUserId;
    private Long authorBotId;

    // depth 0 = direct comment on post, depth 1 = reply to a comment, etc.
    private Integer depthLevel = 0;

    // needed for the bot-human cooldown check
    private Long targetHumanId;
}
