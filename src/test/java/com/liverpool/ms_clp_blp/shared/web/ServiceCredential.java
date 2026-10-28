package com.liverpool.ms_clp_blp.shared.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.test.context.TestPropertySource;

/**
 * Supplies the inbound HTTP Basic credential so a test that boots the application context can start
 * at all.
 *
 * <p>{@link SecurityConfig} refuses to build its {@code UserDetailsService} when the credential is
 * blank, which means <em>every</em> {@code @SpringBootTest} needs this — including the wire tests,
 * which never touch an inbound endpoint and fail only because the context does not come up. That is
 * the intended cost of failing fast rather than starting open.</p>
 *
 * <p>This annotation only configures the credential; it does not authenticate anything. A test that
 * makes MockMvc calls wants {@link WithServiceCredential} instead, and one that goes over a real
 * socket sends {@link TestServiceCredential#AUTHORIZATION_HEADER} itself.</p>
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@TestPropertySource(properties = {
        TestServiceCredential.USERNAME_PROPERTY,
        TestServiceCredential.PASSWORD_PROPERTY})
public @interface ServiceCredential {
}
