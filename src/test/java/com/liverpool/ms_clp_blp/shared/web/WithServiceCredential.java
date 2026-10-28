package com.liverpool.ms_clp_blp.shared.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;

/**
 * Makes a {@code @WebMvcTest} slice that imports {@link SecurityConfig} behave as an authenticated
 * caller, which is what every controller test needs now that the chain requires HTTP Basic.
 *
 * <p>Two things are needed and they are easy to confuse. The {@link TestPropertySource} below — the
 * same pair {@link ServiceCredential} carries — supplies the credential so {@code SecurityConfig} can
 * build its {@code UserDetailsService} at all; without it the context fails to start, before any
 * request is made. {@link WithMockUser} then pre-populates the security context so the requests the
 * test already writes are not rejected, which is far less invasive than adding
 * {@code with(httpBasic(...))} to every {@code mockMvc.perform} in the suite.</p>
 *
 * <p>A test that wants to assert the <em>unauthenticated</em> behaviour must not use this — see
 * {@code SecurityConfigTest}, which supplies the properties itself and sends no credential.</p>
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@TestPropertySource(properties = {
        TestServiceCredential.USERNAME_PROPERTY,
        TestServiceCredential.PASSWORD_PROPERTY})
@WithMockUser
public @interface WithServiceCredential {
}
