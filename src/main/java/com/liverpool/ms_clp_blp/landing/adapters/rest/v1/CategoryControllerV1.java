package com.liverpool.ms_clp_blp.landing.adapters.rest.v1;

import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.liverpool.ms_clp_blp.landing.models.CategoryData;
import com.liverpool.ms_clp_blp.landing.ports.LandingService;
import com.liverpool.ms_clp_blp.shared.errors.GlobalExceptionHandler;
import com.liverpool.ms_clp_blp.shared.session.SessionContextPort;
import com.liverpool.ms_clp_blp.shared.web.EdgeCacheControl;

import lombok.RequiredArgsConstructor;

/**
 * Inbound adapter for {@code GET /categories?categoryId=…}: one category with its children, read from
 * the category indexer and rendered as the landing pages' {@code categoryData}.
 *
 * <p>Thin by design (Rule 18): the brand comes from {@code x-brand-id} via {@link SessionContextPort}
 * (falling back to {@code cms.default-brand}), the read is {@link LandingService#getCategory}, and the
 * shape is {@link CategoryDataMapper}'s — the very mapper behind {@code categoryData}, so the
 * {@code metadata} of every node is built identically on both endpoints.</p>
 *
 * <p><strong>Edge caching.</strong> The answer depends only on brand and category — no session,
 * cookie or login declaration — so every 200 is {@link EdgeCacheControl#SHARED}. Errors are translated
 * by {@link GlobalExceptionHandler} and never reach this builder, so they keep the uncacheable
 * security default.</p>
 */
@RestController
@Validated
@RequiredArgsConstructor
public class CategoryControllerV1 implements CategoryApiV1 {

    private final LandingService landingService;
    private final SessionContextPort sessionContextPort;
    private final CategoryDataMapper categoryDataMapper;

    /**
     * {@inheritDoc}
     *
     * <p>{@code required = false} on purpose: a missing id then reaches the interface's
     * {@code @NotBlank} and answers the standard 400 {@code VALIDATION_ERROR}.</p>
     */
    @GetMapping("/categories")
    @Override
    public ResponseEntity<CategoryResponse> getCategory(@RequestParam(required = false) String categoryId) {
        String brand = sessionContextPort.currentContext().brand();
        CategoryData category = landingService.getCategory(brand, categoryId);

        return ResponseEntity.ok()
                .header(LandingControllerV1.HEADER_REQUEST_ID, MDC.get("requestId"))
                .cacheControl(EdgeCacheControl.SHARED)
                .body(new CategoryResponse(categoryDataMapper.toMap(category)));
    }
}
