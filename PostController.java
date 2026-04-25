package com.grid07.controller;

import com.grid07.dto.AddCommentRequest;
import com.grid07.dto.ApiResponse;
import com.grid07.dto.CreatePostRequest;
import com.grid07.dto.LikePostRequest;
import com.grid07.entity.Comment;
import com.grid07.entity.Post;
import com.grid07.service.PostService;
import com.grid07.service.ViralityService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/posts")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;
    private final ViralityService viralityService;

    // POST /api/posts
    @PostMapping
    public ResponseEntity<ApiResponse<Post>> createPost(@Valid @RequestBody CreatePostRequest req) {
        Post post = postService.createPost(req);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok("post created", post));
    }

    // POST /api/posts/{postId}/comments
    @PostMapping("/{postId}/comments")
    public ResponseEntity<ApiResponse<Comment>> addComment(
            @PathVariable Long postId,
            @Valid @RequestBody AddCommentRequest req) {

        Comment comment = postService.addComment(postId, req);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok("comment added", comment));
    }

    // POST /api/posts/{postId}/like
    @PostMapping("/{postId}/like")
    public ResponseEntity<ApiResponse<Void>> likePost(
            @PathVariable Long postId,
            @Valid @RequestBody LikePostRequest req) {

        postService.likePost(postId, req.getUserId());
        return ResponseEntity.ok(ApiResponse.ok("post liked", null));
    }

    // GET /api/posts/{postId}/virality - handy for checking scores while testing
    @GetMapping("/{postId}/virality")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getVirality(@PathVariable Long postId) {
        long score = viralityService.getScore(postId);

        Map<String, Long> result = new HashMap<>();
        result.put("viralityScore", score);

        return ResponseEntity.ok(ApiResponse.ok("ok", result));
    }
}
