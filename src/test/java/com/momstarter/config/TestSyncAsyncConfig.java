package com.momstarter.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

/**
 * Replaces the async task executor with a synchronous one for tests.
 *
 * <p>Spring's {@code @Async} proxy submits tasks to the application's {@link TaskExecutor}.
 * In production, tasks run on a thread-pool thread (truly async). In tests annotated with
 * {@code @Transactional}, the test thread owns an uncommitted transaction that is invisible
 * to thread-pool threads, causing mock verifications to race and JPA finds to miss test data.
 *
 * <p>By providing a {@link SyncTaskExecutor} the {@code @Async} proxy runs tasks <em>immediately
 * in the calling thread</em>. This means:
 * <ul>
 *   <li>JPA calls inside the async method see the test's (uncommitted) transaction data.</li>
 *   <li>Mockito {@code verify()} calls succeed immediately after the request returns.</li>
 *   <li>Timing-parity tests measure the synchronous path, which is a valid proxy for
 *       "both branches do the same amount of work" in the test environment.</li>
 * </ul>
 *
 * <p>Import this class into any {@code @SpringBootTest} that needs predictable async behaviour:
 * {@code @Import(TestSyncAsyncConfig.class)}.
 */
@TestConfiguration
public class TestSyncAsyncConfig {

    @Bean
    @Primary
    public TaskExecutor taskExecutor() {
        return new SyncTaskExecutor();
    }
}
