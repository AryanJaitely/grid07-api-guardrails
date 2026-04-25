package com.grid07.controller;

import com.grid07.dto.ApiResponse;
import com.grid07.entity.Bot;
import com.grid07.entity.User;
import com.grid07.repository.BotRepository;
import com.grid07.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

// helper endpoints just for seeding test data, not part of the main assignment spec
@RestController
@RequestMapping("/api/setup")
@RequiredArgsConstructor
public class SetupController {

    private final UserRepository userRepository;
    private final BotRepository botRepository;

    @PostMapping("/users")
    public ResponseEntity<ApiResponse<User>> createUser(@RequestBody Map<String, Object> body) {
        User user = new User();
        user.setUsername((String) body.get("username"));
        user.setIsPremium(body.get("isPremium") != null && (Boolean) body.get("isPremium"));

        User saved = userRepository.save(user);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("user created", saved));
    }

    @PostMapping("/bots")
    public ResponseEntity<ApiResponse<Bot>> createBot(@RequestBody Map<String, Object> body) {
        Bot bot = new Bot();
        bot.setName((String) body.get("name"));
        bot.setPersonaDescription((String) body.get("personaDescription"));

        Bot saved = botRepository.save(bot);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("bot created", saved));
    }

    @GetMapping("/users")
    public ResponseEntity<ApiResponse<List<User>>> getUsers() {
        return ResponseEntity.ok(ApiResponse.ok("ok", userRepository.findAll()));
    }

    @GetMapping("/bots")
    public ResponseEntity<ApiResponse<List<Bot>>> getBots() {
        return ResponseEntity.ok(ApiResponse.ok("ok", botRepository.findAll()));
    }
}
