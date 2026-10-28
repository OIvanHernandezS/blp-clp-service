package com.liverpool.ms_clp_blp.shared.config;

import java.util.Locale;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The deployment environment this instance is running as, normalised to upper case.
 *
 * <p>Exists because per-brand configuration is keyed by {@code <BRAND>_<PROFILE>} (for example
 * {@code LP_QA}), so one shared store can serve every environment without a QA document ever being
 * read by production.</p>
 *
 * <p><strong>Why the property and not {@code Environment#getActiveProfiles()}:</strong>
 * {@code application.yaml} declares {@code spring.profiles.include: ${LOG_FORMAT:}}, so setting
 * {@code LOG_FORMAT=pretty} locally adds {@code pretty} to the <em>active profiles array</em> while
 * leaving the {@code spring.profiles.active} property untouched. Reading the array would therefore
 * yield {@code [dev, pretty]} and could resolve the environment to {@code PRETTY}; reading the
 * property cannot. {@link #NON_ENVIRONMENT_PROFILES} is a second belt for the case where someone
 * lists it in {@code SPRING_PROFILES_ACTIVE} explicitly.</p>
 */
@Component
public class RuntimeProfile {

    /** Used when no profile is set at all — matches the Dockerfile's documented {@code dev} default. */
    static final String DEFAULT_PROFILE = "DEV";

    /** Profiles that switch behaviour without naming an environment; never a valid answer here. */
    private static final Set<String> NON_ENVIRONMENT_PROFILES = Set.of("PRETTY");

    private static final String SEPARATOR = ",";

    private final String name;

    /**
     * @param activeProfiles raw {@code spring.profiles.active} value; may be empty or comma-separated
     */
    public RuntimeProfile(@Value("${spring.profiles.active:}") String activeProfiles) {
        this.name = normalize(activeProfiles);
    }

    /**
     * @return the environment name in upper case, e.g. {@code QA}; never blank
     */
    public String name() {
        return name;
    }

    /**
     * Picks the first segment that actually names an environment. A comma-separated value keeps its
     * leading entry (Spring's own precedence order), so {@code qa,pretty} resolves to {@code QA}.
     */
    private static String normalize(String activeProfiles) {
        if (activeProfiles == null || activeProfiles.isBlank()) {
            return DEFAULT_PROFILE;
        }
        for (String segment : activeProfiles.split(SEPARATOR)) {
            String candidate = segment.trim().toUpperCase(Locale.ROOT);
            if (!candidate.isEmpty() && !NON_ENVIRONMENT_PROFILES.contains(candidate)) {
                return candidate;
            }
        }
        return DEFAULT_PROFILE;
    }
}
