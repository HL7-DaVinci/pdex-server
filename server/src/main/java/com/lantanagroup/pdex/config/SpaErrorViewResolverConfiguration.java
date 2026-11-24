package com.lantanagroup.pdex.config;

import org.springframework.boot.autoconfigure.web.servlet.error.ErrorViewResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.ModelAndView;

/**
 * Forwards a GET that ends in a 404 to the frontend, so a reload of a client route such as
 * /patients/123 loads the app. Spring Boot calls error view resolvers only for requests whose
 * Accept header names text/html. Browsers request scripts, stylesheets, and images with other
 * Accept headers, so a missing one keeps its 404. Any path qualifies because a client route
 * can end in a FHIR id, and FHIR ids can contain dots.
 */
@Configuration
public class SpaErrorViewResolverConfiguration {

    @Bean
    ErrorViewResolver spaErrorViewResolver() {
        return (request, status, model) ->
            status == HttpStatus.NOT_FOUND && HttpMethod.GET.matches(request.getMethod())
                ? new ModelAndView("forward:/index.html", model, HttpStatus.OK)
                : null;
    }
}
