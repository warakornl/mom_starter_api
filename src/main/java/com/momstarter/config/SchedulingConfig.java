package com.momstarter.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Activates Spring's {@code @Scheduled} task infrastructure (Phase 3 hard-erasure prod-gate).
 *
 * <p>{@code @EnableScheduling} must appear on exactly one {@link Configuration @Configuration}
 * class in the application context. Placing it here (alongside {@link SecurityConfig},
 * {@link JwtKeyConfig}, {@link TimeConfig}) keeps all cross-cutting wiring in one package.
 *
 * <h2>Multi-pod note — ShedLock deferred</h2>
 * <p>In a single-pod deployment, {@code @EnableScheduling} is sufficient: one pod fires
 * each cron. When the application scales to multiple pods on AWS ECS/EKS, every pod fires
 * {@code @Scheduled} crons simultaneously — the hard-erasure queries are idempotent, but
 * duplicate work occurs. Before scaling, choose one of:
 * <ul>
 *   <li><strong>ShedLock</strong> — distributed lock backed by a shared DB table (or Redis);
 *       ensures only the lock-holder runs the scheduled task per firing cycle.</li>
 *   <li><strong>AWS EventBridge → internal endpoint</strong> — EventBridge fires a single HTTP
 *       call to one pod (load-balancer-selected); no shared state needed.</li>
 * </ul>
 * Deferred per {@code consent-hardgate-erasure-design.md §2.2}.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
    // No additional bean declarations — @EnableScheduling is the sole purpose of this class.
}
