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
package org.opencrawling.runtime.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.opencrawling.core.event.JobExecutionCompletedEvent;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.core.reporting.DocumentLedgerEntry;
import org.opencrawling.core.reporting.JobReportSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Service providing reporting retrieval, document-level filtering and pagination,
 * file export resolution, and serverless DuckDB analytical queries over Parquet files.
 */
@Service
public class JobReportService {

    private static final Logger log = LoggerFactory.getLogger(JobReportService.class);

    private final JobReportProperties properties;
    private final JobLedgerAccumulator accumulator;
    private final ObjectMapper objectMapper;

    public JobReportService(JobReportProperties properties, JobLedgerAccumulator accumulator) {
        this.properties = properties;
        this.accumulator = accumulator;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    public List<JobReportSummary> getAllSummaries() {
        Set<File> searchDirs = getCandidateSearchDirs();
        Map<String, JobReportSummary> summariesByExecId = new LinkedHashMap<>();

        for (File dir : searchDirs) {
            File[] summaryFiles = dir.listFiles((d, name) -> name.startsWith("summary_") && name.endsWith(".json"));
            if (summaryFiles != null) {
                for (File file : summaryFiles) {
                    try {
                        JobReportSummary summary = objectMapper.readValue(file, JobReportSummary.class);
                        summariesByExecId.putIfAbsent(summary.executionId(), summary);
                    } catch (Exception e) {
                        log.warn("Failed to read report summary from {}: {}", file.getName(), e.getMessage());
                    }
                }
            }
        }

        List<JobReportSummary> list = new ArrayList<>(summariesByExecId.values());
        // Sort descending by start time
        list.sort((a, b) -> b.startTime().compareTo(a.startTime()));
        return list;
    }

    public Optional<JobReportSummary> getSummary(String executionId) {
        Set<File> searchDirs = getCandidateSearchDirs();

        for (File dir : searchDirs) {
            File file = new File(dir, String.format("summary_%s.json", executionId));
            if (file.exists()) {
                try {
                    return Optional.of(objectMapper.readValue(file, JobReportSummary.class));
                } catch (Exception e) {
                    log.error("Failed to parse summary for execution {}: {}", executionId, e.getMessage());
                }
            }
        }

        JobExecutionCompletedEvent event = accumulator.getCompletedEvent(executionId);
        if (event != null) {
            long durationMs = java.time.Duration.between(event.startTime(), event.endTime()).toMillis();
            String status = event.failedDocuments() == 0 ? "SUCCESS"
                    : (event.successfulDocuments() > 0 ? "PARTIAL" : "FAILED");
            return Optional.of(new JobReportSummary(
                    event.jobId(),
                    event.executionId(),
                    event.jobName(),
                    event.pipelineMode(),
                    event.repositoryConnector(),
                    event.outputConnector(),
                    status,
                    event.startTime(),
                    event.endTime(),
                    durationMs,
                    event.totalDocuments(),
                    event.successfulDocuments(),
                    event.failedDocuments(),
                    event.skippedDocuments(),
                    (Long) event.summaryMetrics().get("totalChunks"),
                    (Long) event.summaryMetrics().get("totalTokens"),
                    (Double) event.summaryMetrics().get("estimatedEmbeddingCostUsd"),
                    (String) event.summaryMetrics().get("embeddingModel"),
                    (Long) event.summaryMetrics().get("totalBinaryBytes"),
                    (Long) event.summaryMetrics().get("totalReclaimedBytes"),
                    (Long) event.summaryMetrics().get("safePrunedCount"),
                    (Long) event.summaryMetrics().get("verifiedChecksumCount"),
                    null, null, null
            ));
        }

        return Optional.empty();
    }

    public PaginatedLedgerResponse getDocumentEntries(String executionId, String statusFilter, String query, int page, int size) {
        List<DocumentLedgerEntry> all = accumulator.getEntries(executionId);
        if (all == null || all.isEmpty()) {
            all = loadEntriesFromDisk(executionId);
        }

        List<DocumentLedgerEntry> filtered = all.stream()
                .filter(entry -> {
                    if (statusFilter != null && !statusFilter.isBlank() && !"ALL".equalsIgnoreCase(statusFilter)) {
                        if (!statusFilter.equalsIgnoreCase(entry.status())) {
                            return false;
                        }
                    }
                    if (query != null && !query.isBlank()) {
                        String q = query.toLowerCase();
                        boolean matchId = entry.documentId() != null && entry.documentId().toLowerCase().contains(q);
                        boolean matchUri = entry.sourceUri() != null && entry.sourceUri().toLowerCase().contains(q);
                        if (!matchId && !matchUri) {
                            return false;
                        }
                    }
                    return true;
                })
                .collect(Collectors.toList());

        int total = filtered.size();
        int fromIndex = Math.min(page * size, total);
        int toIndex = Math.min(fromIndex + size, total);

        List<DocumentLedgerEntry> pageItems = filtered.subList(fromIndex, toIndex);
        return new PaginatedLedgerResponse(pageItems, total, page, size);
    }

    public Optional<File> getExportFile(String executionId, String format) {
        Set<File> searchDirs = getCandidateSearchDirs();
        String fmt = format != null ? format.toLowerCase() : "json";

        for (File dir : searchDirs) {
            Optional<File> found = switch (fmt) {
                case "csv" -> {
                    File file = new File(dir, String.format("manifest_%s.csv", executionId));
                    yield file.exists() ? Optional.of(file) : Optional.empty();
                }
                case "parquet" -> {
                    File[] matches = findParquetFiles(dir, executionId);
                    yield (matches != null && matches.length > 0) ? Optional.of(matches[0]) : Optional.empty();
                }
                case "avro" -> {
                    File[] matches = findAvroFiles(dir, executionId);
                    yield (matches != null && matches.length > 0) ? Optional.of(matches[0]) : Optional.empty();
                }
                default -> {
                    File file = new File(dir, String.format("summary_%s.json", executionId));
                    yield file.exists() ? Optional.of(file) : Optional.empty();
                }
            };
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private File[] findParquetFiles(File baseDir, String executionId) {
        List<File> result = new ArrayList<>();
        findFilesRecursively(baseDir, executionId + ".parquet", result);
        return result.toArray(new File[0]);
    }

    private File[] findAvroFiles(File baseDir, String executionId) {
        List<File> result = new ArrayList<>();
        findFilesRecursively(baseDir, executionId + ".avro", result);
        return result.toArray(new File[0]);
    }

    private void findFilesRecursively(File dir, String suffix, List<File> result) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                findFilesRecursively(f, suffix, result);
            } else if (f.getName().endsWith(suffix)) {
                result.add(f);
            }
        }
    }

    public Set<File> getCandidateSearchDirs() {
        File primary = properties.getStorage().resolveLocalDir();
        Set<File> searchDirs = new LinkedHashSet<>();
        if (primary != null && primary.exists()) {
            searchDirs.add(primary);
        }
        // Only include workspace fallbacks if primary configuration is relative (local development mode)
        File configured = new File(properties.getStorage().getLocalDir());
        if (!configured.isAbsolute()) {
            for (String fallback : List.of("target/reports", "oc-runtime/target/reports", "data/reports", "oc-runtime/data/reports")) {
                File f = new File(fallback);
                if (f.exists() && f.isDirectory()) {
                    searchDirs.add(f);
                }
            }
        }
        return searchDirs;
    }

    public QueryResult executeDuckDbQuery(String sql) throws Exception {
        log.info("Executing DuckDB SQL query: {}", sql);
        File reportsDir = properties.getStorage().resolveLocalDir();

        if (reportsDir != null && reportsDir.exists()) {
            cleanupEmptyParquetFiles(reportsDir);
        }
        for (File dir : getCandidateSearchDirs()) {
            cleanupEmptyParquetFiles(dir);
        }

        Set<String> searchPaths = new LinkedHashSet<>();
        if (reportsDir != null && reportsDir.exists()) {
            searchPaths.add(reportsDir.getAbsolutePath());
            if (reportsDir.getParentFile() != null) {
                searchPaths.add(reportsDir.getParentFile().getAbsolutePath());
            }
        }
        for (File dir : getCandidateSearchDirs()) {
            searchPaths.add(dir.getAbsolutePath());
            if (dir.getParentFile() != null) {
                searchPaths.add(dir.getParentFile().getAbsolutePath());
            }
        }
        searchPaths.add(new File(".").getAbsolutePath());

        String resolvedSql = resolveParquetPathInSql(sql, reportsDir, searchPaths);

        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:");
             Statement stmt = conn.createStatement()) {

            if (!searchPaths.isEmpty()) {
                String pathsJoined = String.join(",", searchPaths);
                stmt.execute("SET file_search_path = '" + pathsJoined.replace("'", "''") + "';");
            }

            try (ResultSet rs = stmt.executeQuery(resolvedSql)) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                List<String> columns = new ArrayList<>();
                for (int i = 1; i <= colCount; i++) {
                    columns.add(meta.getColumnLabel(i));
                }

                List<List<Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    List<Object> row = new ArrayList<>();
                    for (int i = 1; i <= colCount; i++) {
                        row.add(rs.getObject(i));
                    }
                    rows.add(row);
                }

                return new QueryResult(columns, rows);
            }
        }
    }

    private String resolveParquetPathInSql(String sql, File reportsDir, Set<String> searchPaths) {
        if (sql == null || !sql.toLowerCase().contains("read_parquet")) {
            return sql;
        }

        Pattern pattern = Pattern.compile("(?i)read_parquet\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)");
        Matcher matcher = pattern.matcher(sql);
        StringBuilder sb = new StringBuilder();

        while (matcher.find()) {
            String path = matcher.group(1);
            File candidate = new File(path);

            if (!candidate.isAbsolute()) {
                String foundPath = null;
                // 1. Check in reportsDir first
                if (reportsDir != null) {
                    File f = new File(reportsDir, path);
                    if (f.exists()) {
                        foundPath = f.getAbsolutePath();
                    }
                }
                // 2. Check candidate search paths
                if (foundPath == null) {
                    for (String basePath : searchPaths) {
                        File f = new File(basePath, path);
                        if (f.exists()) {
                            foundPath = f.getAbsolutePath();
                            break;
                        }
                    }
                }
                // 3. Fallback: if filename ends with .parquet, find recursively across candidate dirs
                if (foundPath == null && path.endsWith(".parquet") && !path.contains("*")) {
                    String fileName = new File(path).getName();
                    for (String basePath : searchPaths) {
                        File base = new File(basePath);
                        if (base.exists() && base.isDirectory()) {
                            List<File> matches = new ArrayList<>();
                            findFilesRecursively(base, fileName, matches);
                            if (!matches.isEmpty()) {
                                foundPath = matches.get(0).getAbsolutePath();
                                break;
                            }
                        }
                    }
                }

                // 4. Wildcard glob pattern resolution: if path contains '*', resolve against reportsDir or searchPaths
                if (foundPath == null && path.contains("*")) {
                    if (reportsDir != null && reportsDir.exists()) {
                        foundPath = new File(reportsDir, path).getPath();
                    } else {
                        for (String basePath : searchPaths) {
                            File base = new File(basePath);
                            if (base.exists() && base.isDirectory()) {
                                foundPath = new File(base, path).getPath();
                                break;
                            }
                        }
                    }
                }

                if (foundPath != null) {
                    String escaped = foundPath.replace("'", "''");
                    matcher.appendReplacement(sb, Matcher.quoteReplacement("read_parquet('" + escaped + "')"));
                    continue;
                }
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private void cleanupEmptyParquetFiles(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                cleanupEmptyParquetFiles(f);
            } else if (f.getName().endsWith(".parquet") && f.length() == 0) {
                log.warn("Removing orphaned 0-byte parquet file: {}", f.getAbsolutePath());
                f.delete();
                File crc = new File(f.getParentFile(), "." + f.getName() + ".crc");
                if (crc.exists()) {
                    crc.delete();
                }
            }
        }
    }

    public List<DocumentLedgerEntry> loadEntriesFromDisk(String executionId) {
        // 1. Try reading compliance CSV manifest
        Optional<File> csvOpt = getExportFile(executionId, "csv");
        if (csvOpt.isPresent()) {
            List<DocumentLedgerEntry> fromCsv = loadEntriesFromCsv(csvOpt.get());
            if (!fromCsv.isEmpty()) {
                log.info("Loaded {} document ledger entries from CSV manifest {}", fromCsv.size(), csvOpt.get().getName());
                return fromCsv;
            }
        }

        // 2. Fallback: try DuckDB query on columnar Parquet
        Optional<File> parquetOpt = getExportFile(executionId, "parquet");
        if (parquetOpt.isPresent()) {
            try {
                List<DocumentLedgerEntry> fromParquet = loadEntriesFromParquet(parquetOpt.get());
                if (!fromParquet.isEmpty()) {
                    log.info("Loaded {} document ledger entries from Parquet file {}", fromParquet.size(), parquetOpt.get().getName());
                    return fromParquet;
                }
            } catch (Exception e) {
                log.warn("Failed to load ledger entries from parquet file {}: {}", parquetOpt.get().getName(), e.getMessage());
            }
        }

        return Collections.emptyList();
    }

    private List<DocumentLedgerEntry> loadEntriesFromCsv(File file) {
        List<DocumentLedgerEntry> result = new ArrayList<>();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file, java.nio.charset.StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null) return result;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> cols = parseCsvLine(line);
                if (cols.size() < 8) continue;
                try {
                    String jobId = cols.get(0);
                    String execId = cols.get(1);
                    PipelineMode mode = PipelineMode.parseStrict(cols.get(2));
                    String docId = cols.get(3);
                    String sourceUri = cols.get(4);
                    String connectorSource = cols.get(5);
                    String connectorTarget = cols.get(6);
                    String status = cols.get(7);
                    long durationMs = parseLong(cols.size() > 8 ? cols.get(8) : null, 0L);
                    java.time.Instant timestamp = parseInstant(cols.size() > 9 ? cols.get(9) : null);
                    String errorCode = cols.size() > 10 ? emptyToNull(cols.get(10)) : null;
                    String errorMessage = cols.size() > 11 ? emptyToNull(cols.get(11)) : null;

                    Integer ragChunkCount = cols.size() > 12 ? parseInt(cols.get(12)) : null;
                    Integer ragTokenCount = cols.size() > 13 ? parseInt(cols.get(13)) : null;
                    String ragEmbeddingModel = cols.size() > 14 ? emptyToNull(cols.get(14)) : null;

                    Long migBinarySizeBytes = cols.size() > 15 ? parseLongObj(cols.get(15)) : null;
                    String migSha256Checksum = cols.size() > 16 ? emptyToNull(cols.get(16)) : null;
                    String migTargetKey = cols.size() > 17 ? emptyToNull(cols.get(17)) : null;
                    Boolean migSafePruned = cols.size() > 18 ? parseBoolObj(cols.get(18)) : null;
                    Long migReclaimedBytes = cols.size() > 19 ? parseLongObj(cols.get(19)) : null;

                    result.add(new DocumentLedgerEntry(
                            jobId, execId, mode, docId, sourceUri, connectorSource, connectorTarget,
                            status, durationMs, timestamp, errorCode, errorMessage,
                            ragChunkCount, ragTokenCount, ragEmbeddingModel, 1024, "vector_store",
                            migBinarySizeBytes, migSha256Checksum, null, migTargetKey,
                            null, null, migSafePruned, migReclaimedBytes
                    ));
                } catch (Exception parseEx) {
                    log.debug("Skipping malformed CSV line in {}: {}", file.getName(), parseEx.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse manifest CSV {}: {}", file.getName(), e.getMessage());
        }
        return result;
    }

    private List<DocumentLedgerEntry> loadEntriesFromParquet(File parquetFile) throws Exception {
        String sql = "SELECT * FROM read_parquet('" + parquetFile.getAbsolutePath().replace("'", "''") + "');";
        QueryResult qr = executeDuckDbQuery(sql);
        List<DocumentLedgerEntry> result = new ArrayList<>();
        Map<String, Integer> colIndex = new HashMap<>();
        for (int i = 0; i < qr.columns().size(); i++) {
            colIndex.put(qr.columns().get(i).toLowerCase(), i);
        }

        for (List<Object> row : qr.rows()) {
            try {
                String jobId = getString(row, colIndex, "job_id");
                String execId = getString(row, colIndex, "execution_id");
                PipelineMode mode = PipelineMode.parseStrict(getString(row, colIndex, "pipeline_mode"));
                String docId = getString(row, colIndex, "document_id");
                String sourceUri = getString(row, colIndex, "source_uri");
                String connSource = getString(row, colIndex, "connector_source");
                String connTarget = getString(row, colIndex, "connector_target");
                String status = getString(row, colIndex, "status");
                long duration = getLong(row, colIndex, "duration_ms", 0L);
                java.time.Instant timestamp = parseInstant(getString(row, colIndex, "timestamp_utc"));
                String errCode = getString(row, colIndex, "error_code");
                String errMsg = getString(row, colIndex, "error_message");

                Integer ragChunks = getInt(row, colIndex, "rag_chunk_count");
                Integer ragTokens = getInt(row, colIndex, "rag_token_count");
                String ragModel = getString(row, colIndex, "rag_embedding_model");
                Integer ragDims = getInt(row, colIndex, "rag_embedding_dimensions");
                String ragTable = getString(row, colIndex, "rag_vector_table");

                Long migSize = getLongObj(row, colIndex, "mig_binary_size_bytes");
                String migSha = getString(row, colIndex, "mig_sha256_checksum");
                String migBucket = getString(row, colIndex, "mig_target_bucket");
                String migKey = getString(row, colIndex, "mig_target_key");
                Boolean migSidecar = getBoolObj(row, colIndex, "mig_sidecar_written");
                Integer migHttp = getInt(row, colIndex, "mig_callback_http_status");
                Boolean migPruned = getBoolObj(row, colIndex, "mig_safe_pruned");
                Long migReclaimed = getLongObj(row, colIndex, "mig_reclaimed_bytes");

                result.add(new DocumentLedgerEntry(
                        jobId, execId, mode, docId, sourceUri, connSource, connTarget,
                        status, duration, timestamp, errCode, errMsg,
                        ragChunks, ragTokens, ragModel, ragDims, ragTable,
                        migSize, migSha, migBucket, migKey, migSidecar, migHttp, migPruned, migReclaimed
                ));
            } catch (Exception ex) {
                log.debug("Error mapping parquet row to DocumentLedgerEntry: {}", ex.getMessage());
            }
        }
        return result;
    }

    static List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        if (line == null) return values;
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    sb.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                values.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        values.add(sb.toString());
        return values;
    }

    private static String emptyToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    private static long parseLong(String s, long def) {
        if (s == null || s.trim().isEmpty()) return def;
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return def; }
    }

    private static Long parseLongObj(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return null; }
    }

    private static Integer parseInt(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return null; }
    }

    private static Boolean parseBoolObj(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        return Boolean.parseBoolean(s.trim());
    }

    private static java.time.Instant parseInstant(String s) {
        if (s == null || s.trim().isEmpty()) return java.time.Instant.now();
        try { return java.time.Instant.parse(s.trim()); } catch (Exception e) { return java.time.Instant.now(); }
    }

    private static String getString(List<Object> row, Map<String, Integer> colIndex, String col) {
        Integer idx = colIndex.get(col.toLowerCase());
        if (idx == null || idx >= row.size() || row.get(idx) == null) return null;
        return String.valueOf(row.get(idx));
    }

    private static long getLong(List<Object> row, Map<String, Integer> colIndex, String col, long def) {
        Long val = getLongObj(row, colIndex, col);
        return val != null ? val : def;
    }

    private static Long getLongObj(List<Object> row, Map<String, Integer> colIndex, String col) {
        Integer idx = colIndex.get(col.toLowerCase());
        if (idx == null || idx >= row.size() || row.get(idx) == null) return null;
        Object obj = row.get(idx);
        if (obj instanceof Number num) return num.longValue();
        try { return Long.parseLong(String.valueOf(obj).trim()); } catch (Exception e) { return null; }
    }

    private static Integer getInt(List<Object> row, Map<String, Integer> colIndex, String col) {
        Integer idx = colIndex.get(col.toLowerCase());
        if (idx == null || idx >= row.size() || row.get(idx) == null) return null;
        Object obj = row.get(idx);
        if (obj instanceof Number num) return num.intValue();
        try { return Integer.parseInt(String.valueOf(obj).trim()); } catch (Exception e) { return null; }
    }

    private static Boolean getBoolObj(List<Object> row, Map<String, Integer> colIndex, String col) {
        Integer idx = colIndex.get(col.toLowerCase());
        if (idx == null || idx >= row.size() || row.get(idx) == null) return null;
        Object obj = row.get(idx);
        if (obj instanceof Boolean bool) return bool;
        return Boolean.parseBoolean(String.valueOf(obj).trim());
    }

    public record PaginatedLedgerResponse(
            List<DocumentLedgerEntry> items,
            long total,
            int page,
            int size
    ) {}

    public record QueryResult(
            List<String> columns,
            List<List<Object>> rows
    ) {}
}
