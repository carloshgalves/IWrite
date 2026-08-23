package com.iwrite.health.controller;

import com.iwrite.health.service.DatabaseHealthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
public class PingController {

    private static final String SERVICE_NAME = "iwrite";

    private final DatabaseHealthService databaseHealthService;

    public PingController(DatabaseHealthService databaseHealthService) {
        this.databaseHealthService = databaseHealthService;
    }

    @GetMapping("/ping")
    public ResponseEntity<PingResponse> ping() {
        String timestamp = Instant.now().toString();

        if (databaseHealthService.isHealthy()) {
            return ResponseEntity.ok(new PingResponse("ok", SERVICE_NAME, "up", timestamp));
        }

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new PingResponse("unavailable", SERVICE_NAME, "down", timestamp));
    }

    public record PingResponse(
            String status,
            String service,
            String database,
            String timestamp
    ) {
    }
}
