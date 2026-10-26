package com.bluestaq.notes.config;

import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Keeps JSON request types explicit so numeric and boolean values cannot become note text silently.
 * Other request parsing rules, including unknown-field rejection, are configured in application YAML.
 */
@Configuration
public class JsonConfiguration {
    /**
     * Disables Jackson's numeric and boolean coercion into string-valued request fields.
     *
     * @return a customizer applied to Spring Boot's shared JSON mapper
     */
    @Bean
    Jackson2ObjectMapperBuilderCustomizer strictStrings() {
        return builder -> builder.postConfigurer(mapper -> mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }
}
