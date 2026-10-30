package com.liverpool.ms_clp_blp;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.type;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Enforces the feature-first (vertical slice) dependency rules of ms-clp-blp — the same contract
 * ms-home enforces, for a service with one slice. The build fails when a sub-slice reaches into a
 * sibling or a slice's hexagon core picks up framework dependencies.
 *
 * <p>The contract:
 * <ul>
 *   <li>The {@code landing} slice may depend on {@code shared} and {@code clients}; {@code shared} and
 *       {@code clients} never depend on it. A new top-level slice means a new pair of rules against
 *       {@code landing}, spelled out explicitly.</li>
 *   <li>Block sub-slices ({@code landing.blocks.*}) may depend on landing-owned contracts
 *       ({@code landing.ports}, {@code landing.models}, {@code landing.errors}) but never on landing's
 *       adapters or use cases.</li>
 *   <li>{@code landing} core never depends on {@code landing.blocks.*} — it knows blocks only through
 *       {@code BlockType} and {@code BlockResolutionCatalog}, which it owns.</li>
 *   <li>The {@code landing.brandflags} sub-slice never depends on block sub-slices.</li>
 *   <li>{@code ports} and {@code models} packages stay framework-free.</li>
 * </ul>
 */
@AnalyzeClasses(packages = "com.liverpool.ms_clp_blp", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule sharedAndClientsDoNotDependOnSlices = noClasses()
            .that().resideInAnyPackage("..ms_clp_blp.shared..", "..ms_clp_blp.clients..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..ms_clp_blp.landing..")
            .as("shared and clients must never depend on a feature slice");

    @ArchTest
    static final ArchRule landingCoreDoesNotDependOnBlocks = noClasses()
            .that().resideInAPackage("..ms_clp_blp.landing..")
            .and().resideOutsideOfPackage("..ms_clp_blp.landing.blocks..")
            .should().dependOnClassesThat().resideInAPackage("..ms_clp_blp.landing.blocks..")
            .as("landing core must not depend on block sub-slices (it only knows the BlockType catalog)");

    @ArchTest
    static final ArchRule blocksOnlyUseLandingContracts = noClasses()
            .that().resideInAPackage("..ms_clp_blp.landing.blocks..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..ms_clp_blp.landing.adapters..")
            .as("block sub-slices may use landing ports/models/errors but never landing adapters");

    @ArchTest
    static final ArchRule brandflagsDoesNotDependOnBlocks = noClasses()
            .that().resideInAPackage("..ms_clp_blp.landing.brandflags..")
            .should().dependOnClassesThat().resideInAPackage("..ms_clp_blp.landing.blocks..")
            .as("brandflags may use landing ports/models but never a block sub-slice");

    /**
     * {@code products_list} is the one block with several provider adapters behind a single port
     * (ADR-029). Keeping every implementation inside the slice is what stops a provider drifting out
     * into a sibling block.
     */
    @ArchTest
    static final ArchRule productsListPortsLiveInTheProductsListSlice = classes()
            .that().implement(
                    com.liverpool.ms_clp_blp.landing.blocks.productslist.ports.ProductsListPort.class)
            .should().resideInAPackage("..ms_clp_blp.landing.blocks.productslist.adapters..")
            .as("every products_list provider adapter belongs to the productslist slice");

    /**
     * The best-effort upstreams of a landing page — category, redirect, favourite store and blacklist —
     * are landing-owned ports. Their implementations stay in {@code landing.adapters}, which blocks can
     * never see, so no block can bypass the port (and its fail-open contract) by calling a client.
     */
    @ArchTest
    static final ArchRule landingOutboundPortImplementationsLiveInLandingAdapters = classes()
            .that().implement(com.liverpool.ms_clp_blp.landing.ports.CategoryPort.class)
            .or().implement(com.liverpool.ms_clp_blp.landing.ports.CategoryRedirectPort.class)
            .or().implement(com.liverpool.ms_clp_blp.landing.ports.FavoriteStorePort.class)
            .or().implement(com.liverpool.ms_clp_blp.landing.ports.RestrictedProductsPort.class)
            .or().implement(com.liverpool.ms_clp_blp.landing.ports.ContentPort.class)
            .should().resideInAPackage("..ms_clp_blp.landing.adapters..")
            .as("landing outbound port implementations belong to landing adapters");

    @ArchTest
    static final ArchRule portsAndModelsAreFrameworkFree = noClasses()
            .that().resideInAnyPackage("..ports..", "..models..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "jakarta.servlet..")
            .as("ports and models stay framework-free — the hexagon core must not leak Spring");

    @ArchTest
    static final ArchRule controllersLiveInRestAdapters = classes()
            .that().areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
            .should().resideInAPackage("..adapters.rest..")
            .as("@RestController classes belong in an adapters/rest package");

    @ArchTest
    static final ArchRule blockResolversLiveInBlockAdapters = classes()
            .that().implement(com.liverpool.ms_clp_blp.landing.ports.BlockResolver.class)
            .should().resideInAPackage("..ms_clp_blp.landing.blocks.*.adapters..")
            .as("BlockResolver strategies belong to a block sub-slice's adapters package");

    @ArchTest
    static final ArchRule blockParamsBuildersLiveInBlockAdapters = classes()
            .that().implement(com.liverpool.ms_clp_blp.landing.ports.BlockParamsBuilder.class)
            .should().resideInAPackage("..ms_clp_blp.landing.blocks.*.adapters..")
            .as("BlockParamsBuilder strategies belong to a block sub-slice's adapters package");

    /**
     * {@code SessionContextPort.currentContext()} is guest-shaped: only {@code GET /blp|clp/**} folds
     * the caller's {@code x-is-logged-in} declaration into {@code authenticated}. The products-list
     * endpoint does not, so reading the login signal there would silently report every logged-in
     * caller as a guest — no exception, no log line, just personalisation quietly switching off.
     *
     * <p>A slice that starts needing the login signal must fold the declaration in itself and come
     * off this list in the same commit.</p>
     */
    @ArchTest
    static final ArchRule identityFreeSlicesDoNotReadIdentity = noClasses()
            .that().resideInAnyPackage("..ms_clp_blp.landing.blocks.productslist..")
            .should().callMethodWhere(
                    target(owner(type(com.liverpool.ms_clp_blp.shared.session.SessionContext.class)))
                            .and(target(nameMatching("authenticated|isGuest"))))
            .as("slices that do not fold in x-is-logged-in must not read the login signal");
}
