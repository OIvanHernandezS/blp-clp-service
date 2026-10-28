package com.liverpool.ms_clp_blp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * Entry point of ms-clp-blp — the brand (BLP) and category (CLP) landing page composition service.
 *
 * <h3>Why Feign scanning stays inside this project</h3>
 * <p>{@code @EnableFeignClients} is pinned to {@code com.liverpool.ms_clp_blp.clients} and must not be
 * widened, for two independent reasons.</p>
 *
 * <p>Adding {@code com.liverpool.clients} to reach {@code platform-commons}' Feign interfaces
 * registers <em>all</em> of them — ContentStack, decomm-indexer, complementary-services,
 * fecha-estimada, Salesforce V2 — and each resolves a {@code url} placeholder
 * ({@code ${api.decomm-service.url}} and friends) that this project does not define, so the context
 * fails to start. Commons' per-client transport configuration is still reused where it earns its
 * keep, but by naming the config class on our own interface
 * ({@code @FeignClient(configuration = JewelFeignConfig.class)}), which needs no scanning.</p>
 *
 * <p>Widening {@code @SpringBootApplication}'s base package to {@code com.liverpool} would be worse
 * still: commons ships {@code com.liverpool.exceptions.AppExceptionHandler}, a
 * {@code @ControllerAdvice} that would then compete with {@code shared/errors/GlobalExceptionHandler}
 * over the error response shape. {@code ms_pdp_search} scans {@code com.liverpool} and does run both.</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableFeignClients(basePackages = "com.liverpool.ms_clp_blp.clients")
public class MsClpBlpApplication {

	public static void main(String[] args) {
		SpringApplication.run(MsClpBlpApplication.class, args);
	}

}
