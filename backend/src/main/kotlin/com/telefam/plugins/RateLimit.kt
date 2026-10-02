package com.telefam.plugins

import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.*

import kotlin.time.Duration.Companion.minutes

/**
 * IP-based rate limiting — a second, independent layer on top of the per-account
 * limits enforced in LoginAttemptService / OtpService. This stops a single IP from
 * hammering *many different* accounts (credential stuffing / enumeration), which
 * per-account counters alone don't cover.
 */
fun Application.configureRateLimiting() {
    install(RateLimit) {
        register(RateLimitName("auth")) {
            rateLimiter(limit = 20, refillPeriod = 1.minutes)
            requestKey { call -> call.request.origin.remoteHost }
        }
        register(RateLimitName("otp")) {
            rateLimiter(limit = 8, refillPeriod = 1.minutes)
            requestKey { call -> call.request.origin.remoteHost }
        }
        register(RateLimitName("media")) {
            rateLimiter(limit = 15, refillPeriod = 1.minutes)
            requestKey { call -> call.request.origin.remoteHost }
        }
        register(RateLimitName("search")) {
            rateLimiter(limit = 60, refillPeriod = 1.minutes)
            requestKey { call -> call.request.origin.remoteHost }
        }
        register(RateLimitName("wallet")) {
            // Withdrawal attempts: few legit requests per minute; tight cap defeats automated draining.
            rateLimiter(limit = 6, refillPeriod = 1.minutes)
            requestKey { call -> call.request.origin.remoteHost }
        }
        register(RateLimitName("posts")) {
            // Chunk endpoints are high-frequency by design (one call per 4MB chunk);
            // the limit is generous per-IP but still blocks upload hammering.
            rateLimiter(limit = 240, refillPeriod = 1.minutes)
            requestKey { call -> call.request.origin.remoteHost }
        }
    }
}
