package com.grid07.service;

import com.grid07.dto.AddCommentRequest;
import com.grid07.dto.CreatePostRequest;
import com.grid07.entity.Bot;
import com.grid07.entity.Comment;
import com.grid07.entity.Post;
import com.grid07.entity.User;
import com.grid07.repository.BotRepository;
import com.grid07.repository.CommentRepository;
import com.grid07.repository.PostRepository;
import com.grid07.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PostService {

    private final PostRepository postRepository;
    private final CommentRepository commentRepository;
    private final UserRepository userRepository;
    private final BotRepository botRepository;
    private final GuardrailService guardrailService;
    private final ViralityService viralityService;
    private final NotificationService notificationService;

    @Transactional
    public Post createPost(CreatePostRequest req) {
        // check if user exists
        User author = userRepository.findById(req.getAuthorUserId()).orElse(null);
        if (author == null) {
            throw new IllegalArgumentException("User not found with id: " + req.getAuthorUserId());
        }

        Post post = new Post();
        post.setAuthorUser(author);
        post.setContent(req.getContent());

        Post saved = postRepository.save(post);
        log.info("new post created with id {} by user {}", saved.getId(), author.getUsername());
        return saved;
    }

    @Transactional
    public Comment addComment(Long postId, AddCommentRequest req) {
        // check if post exists
        Post post = postRepository.findById(postId).orElse(null);
        if (post == null) {
            throw new IllegalArgumentException("Post not found with id: " + postId);
        }

        // figure out if this is a bot comment or a human comment
        boolean isBotComment = req.getAuthorBotId() != null;

        if (isBotComment) {
            return handleBotComment(post, req);
        } else {
            return handleHumanComment(post, req);
        }
    }

    private Comment handleBotComment(Post post, AddCommentRequest req) {
        Bot bot = botRepository.findById(req.getAuthorBotId()).orElse(null);
        if (bot == null) {
            throw new IllegalArgumentException("Bot not found with id: " + req.getAuthorBotId());
        }

        // run all three redis guardrails before touching the database
        // order matters here - check depth first since it's the cheapest check

        // 1. vertical cap check (no redis write, just a comparison)
        guardrailService.checkVerticalCap(req.getDepthLevel());

        // 2. cooldown check - will set a TTL key in redis if ok
        if (req.getTargetHumanId() != null) {
            guardrailService.checkAndSetCooldown(bot.getId(), req.getTargetHumanId());
        }

        // 3. horizontal cap - increments the bot count atomically
        guardrailService.checkAndReserveHorizontalCap(post.getId());

        // if we made it here all guardrails passed, write to postgres
        Comment comment = new Comment();
        comment.setPost(post);
        comment.setAuthorBot(bot);
        comment.setContent(req.getContent());
        comment.setDepthLevel(req.getDepthLevel());

        Comment saved;
        try {
            saved = commentRepository.save(comment);
        } catch (Exception e) {
            // db write failed after we already incremented the redis counter
            // need to release that slot or the count will be wrong
            guardrailService.releaseHorizontalCap(post.getId());
            log.error("db save failed for bot comment on post {}", post.getId(), e);
            throw e;
        }

        // update virality score
        viralityService.onBotReply(post.getId());

        // notify the post author if the post was written by a human
        if (post.getAuthorUser() != null) {
            notificationService.handleBotInteraction(
                post.getAuthorUser().getId(),
                bot.getName(),
                post.getId()
            );
        }

        log.info("bot comment saved: commentId={} postId={} bot={} depth={}",
            saved.getId(), post.getId(), bot.getName(), req.getDepthLevel());
        return saved;
    }

    private Comment handleHumanComment(Post post, AddCommentRequest req) {
        User user = userRepository.findById(req.getAuthorUserId()).orElse(null);
        if (user == null) {
            throw new IllegalArgumentException("User not found with id: " + req.getAuthorUserId());
        }

        Comment comment = new Comment();
        comment.setPost(post);
        comment.setAuthorUser(user);
        comment.setContent(req.getContent());
        comment.setDepthLevel(req.getDepthLevel());

        Comment saved = commentRepository.save(comment);

        // human comment is worth 50 virality points
        viralityService.onHumanComment(post.getId());

        log.info("human comment saved: commentId={} postId={} user={}", saved.getId(), post.getId(), user.getUsername());
        return saved;
    }

    @Transactional(readOnly = true)
    public void likePost(Long postId, Long userId) {
        // just check they exist, we're not storing likes in the db (only virality score in redis)
        boolean postExists = postRepository.existsById(postId);
        boolean userExists = userRepository.existsById(userId);

        if (!postExists) {
            throw new IllegalArgumentException("Post not found with id: " + postId);
        }
        if (!userExists) {
            throw new IllegalArgumentException("User not found with id: " + userId);
        }

        // human like = +20 virality
        viralityService.onHumanLike(postId);
        log.info("post {} liked by user {}", postId, userId);
    }
}
