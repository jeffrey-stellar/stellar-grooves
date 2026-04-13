package com.stellarideas.grooves.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class RateLimitConfig {

    private static final Logger logger = LoggerFactory.getLogger(RateLimitConfig.class);

    @Bean
    @ConditionalOnBean(StringRedisTemplate.class)
    public RateLimitStore redisRateLimitStore(StringRedisTemplate redisTemplate) {
        logger.info("Using Redis-backed rate limiting (distributed)");
        return new RedisRateLimitStore(redisTemplate);
    }

    @Bean
    @ConditionalOnMissingBean(RateLimitStore.class)
    public RateLimitStore inMemoryRateLimitStore() {
        logger.info("Using in-memory rate limiting (single instance only)");
        return new InMemoryRateLimitStore();
    }
}
