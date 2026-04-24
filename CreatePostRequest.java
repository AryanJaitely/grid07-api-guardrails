package com.grid07.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreatePostRequest {

    @NotBlank(message = "content cannot be empty")
    private String content;

    @NotNull(message = "authorUserId is required")
    private Long authorUserId;
}
