/*
 * Copyright © 2026 the original author or authors (piergiorgio@apache.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.opencrawling.runtime.api;

import org.opencrawling.core.reporting.JobReportSummary;
import org.opencrawling.runtime.reporting.JobReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * REST Controller providing execution reporting summaries, paginated document ledgers,
 * one-click file exports (CSV, Parquet, JSON), and serverless analytical querying.
 */
@RestController
@RequestMapping("/api/reports")
@CrossOrigin(origins = "*")
public class ReportController {

    private static final Logger log = LoggerFactory.getLogger(ReportController.class);
    private static final Pattern SAFE_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,128}$");
    private static final Set<String> ALLOWED_EXPORT_FORMATS = Set.of("csv", "parquet", "avro", "json");

    private final JobReportService reportService;

    public ReportController(JobReportService reportService) {
        this.reportService = reportService;
    }

    private static boolean isValidId(String id) {
        return id != null && SAFE_ID_PATTERN.matcher(id).matches() && !id.contains("..");
    }

    @GetMapping
    public ResponseEntity<List<JobReportSummary>> getAllReports() {
        return ResponseEntity.ok(reportService.getAllSummaries());
    }

    @GetMapping("/{jobId}/{executionId}")
    public ResponseEntity<JobReportSummary> getReport(
            @PathVariable String jobId,
            @PathVariable String executionId) {
        if (!isValidId(jobId) || !isValidId(executionId)) {
            return ResponseEntity.badRequest().build();
        }
        return reportService.getSummary(executionId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{jobId}/{executionId}/documents")
    public ResponseEntity<JobReportService.PaginatedLedgerResponse> getDocumentLedger(
            @PathVariable String jobId,
            @PathVariable String executionId,
            @RequestParam(defaultValue = "ALL") String status,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        if (!isValidId(jobId) || !isValidId(executionId)) {
            return ResponseEntity.badRequest().build();
        }
        int safePage = Math.max(0, Math.min(page, 100_000));
        int safeSize = Math.max(1, Math.min(size, 1_000));
        return ResponseEntity.ok(reportService.getDocumentEntries(executionId, status, query, safePage, safeSize));
    }

    @GetMapping("/{jobId}/{executionId}/export")
    public ResponseEntity<Resource> exportReport(
            @PathVariable String jobId,
            @PathVariable String executionId,
            @RequestParam(defaultValue = "json") String format) {
        if (!isValidId(jobId) || !isValidId(executionId)) {
            return ResponseEntity.badRequest().build();
        }
        String fmt = format != null ? format.trim().toLowerCase() : "json";
        if (!ALLOWED_EXPORT_FORMATS.contains(fmt)) {
            return ResponseEntity.badRequest().build();
        }

        return reportService.getExportFile(executionId, fmt)
                .filter(reportService::isAllowedReportFile)
                .map(file -> {
                    Resource resource = new FileSystemResource(file);
                    String contentType = switch (fmt) {
                        case "csv" -> "text/csv; charset=UTF-8";
                        case "parquet" -> "application/vnd.apache.parquet";
                        case "avro" -> "application/avro";
                        default -> MediaType.APPLICATION_JSON_VALUE;
                    };

                    return ResponseEntity.ok()
                            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getName() + "\"")
                            .contentType(MediaType.parseMediaType(contentType))
                            .body(resource);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/query")
    public ResponseEntity<?> executeQuery(@RequestBody QueryRequest request) {
        if (request == null || request.sql() == null || request.sql().isBlank()) {
            return ResponseEntity.badRequest().body("SQL query must not be empty");
        }
        try {
            JobReportService.validateReadOnlySql(request.sql());
            return ResponseEntity.ok(reportService.executeDuckDbQuery(request.sql()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("Invalid SQL query: " + e.getMessage());
        } catch (Throwable e) {
            log.error("DuckDB SQL query failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body("Query execution failed: " + e.getMessage());
        }
    }

    public record QueryRequest(String sql) {}
}
