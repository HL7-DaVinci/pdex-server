package com.lantanagroup.pdex.api;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping(value = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
public class ApiController {

    @GetMapping("/hello")
    public Map<String, String> hello() {
        Map<String, String> response = new HashMap<>();
        LocalDateTime now = LocalDateTime.now();
        String currentTime = now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        response.put("message", "Hello, World!");
        response.put("currentTime", currentTime);
        
        return response;
    }
}
