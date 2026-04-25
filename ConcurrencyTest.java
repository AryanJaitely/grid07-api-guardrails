package com.grid07;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/*
 * Phase 4 concurrency test.
 * Fires 200 simultaneous bot comment requests at the same post and
 * checks that exactly 100 succeed and 100 are rejected with 429.
 *
 * Before running: make sure postgres and redis are up (docker-compose up -d)
 * and seed a post + bot using the /api/setup endpoints.
 * Update POST_ID and BOT_ID below with real ids from your database.
 *
 * Run with: mvn test -Dtest=ConcurrencyTest
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConcurrencyTest {

    @Autowired
    private MockMvc mockMvc;

    // update these before running
    private static final long POST_ID = 1L;
    private static final long BOT_ID  = 1L;

    @Test
    void shouldAllowExactly100BotReplies() throws InterruptedException {
        int totalRequests = 200;

        // all threads will wait here until we release them all at the same time
        // this simulates truly simultaneous requests hitting the server
        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch allDone = new CountDownLatch(totalRequests);

        AtomicInteger accepted = new AtomicInteger(0);  // 201 responses
        AtomicInteger rejected = new AtomicInteger(0);  // 429 responses

        ExecutorService pool = Executors.newFixedThreadPool(50);

        for (int i = 0; i < totalRequests; i++) {
            pool.submit(() -> {
                try {
                    startSignal.await();  // wait for the starting gun

                    String requestBody = """
                        {
                          "content": "bot comment",
                          "authorBotId": %d,
                          "depthLevel": 0
                        }
                        """.formatted(BOT_ID);

                    int status = mockMvc.perform(
                        post("/api/posts/{postId}/comments", POST_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody)
                    ).andReturn().getResponse().getStatus();

                    if (status == 201) accepted.incrementAndGet();
                    else if (status == 429) rejected.incrementAndGet();

                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                } finally {
                    allDone.countDown();
                }
            });
        }

        startSignal.countDown();  // release all threads at once
        allDone.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        System.out.println("accepted: " + accepted.get() + ", rejected: " + rejected.get());

        assertEquals(100, accepted.get(), "should allow exactly 100 bot replies");
        assertEquals(100, rejected.get(), "should reject exactly 100 requests with 429");
    }
}
