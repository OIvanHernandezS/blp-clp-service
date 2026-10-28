package com.liverpool.ms_clp_blp.landing.adapters.cache;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LandingContentCacheConfig {

    @Bean(destroyMethod = "close")
    public ExecutorService landingContentCacheExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
