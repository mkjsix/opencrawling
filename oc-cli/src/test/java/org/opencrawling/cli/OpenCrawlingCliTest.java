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
package org.opencrawling.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

class OpenCrawlingCliTest {

    private final PrintStream originalOut = System.out;
    private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() {
        System.setOut(new PrintStream(outContent));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
    }

    @Test
    void testRootCommandHelp() {
        CommandLine cmd = new CommandLine(new OpenCrawlingCliCommand());
        int exitCode = cmd.execute("--help");
        assertEquals(0, exitCode);
        assertTrue(outContent.toString().contains("OpenCrawling CLI"));
    }

    @Test
    void testConfigSetAndGet() {
        CommandLine cmd = new CommandLine(new OpenCrawlingCliCommand());
        int exitCodeSet = cmd.execute("config", "set", "--server-url", "http://localhost:8080", "--context", "test-ctx");
        assertEquals(0, exitCodeSet);

        int exitCodeContext = cmd.execute("config", "context");
        assertEquals(0, exitCodeContext);
        assertTrue(outContent.toString().contains("test-ctx"));
    }

    @Test
    void testArchetypeInit() {
        CommandLine cmd = new CommandLine(new OpenCrawlingCliCommand());
        int exitCode = cmd.execute("archetype", "init", "--type", "repository", "--name", "TestCustomConnector", "--output-dir", "target/test-archetype");
        assertEquals(0, exitCode);
        assertTrue(outContent.toString().contains("Successfully generated connector project"));
    }

    @Test
    void testSchemaValidate() throws Exception {
        java.io.File tempFile = java.io.File.createTempFile("test-ois-schema", ".json");
        tempFile.deleteOnExit();
        java.nio.file.Files.writeString(tempFile.toPath(), "{\"name\": \"TestJob\", \"repositoryConnector\": \"FileSystem\"}");

        CommandLine cmd = new CommandLine(new OpenCrawlingCliCommand());
        int exitCode = cmd.execute("schema", "validate", "--file", tempFile.getAbsolutePath());
        assertEquals(0, exitCode);
        assertTrue(outContent.toString().contains("valid"));
    }

    private int validate(String json) throws Exception {
        java.io.File tempFile = java.io.File.createTempFile("test-ois-migration", ".json");
        tempFile.deleteOnExit();
        java.nio.file.Files.writeString(tempFile.toPath(), json);
        return new CommandLine(new OpenCrawlingCliCommand()).execute("schema", "validate", "--file", tempFile.getAbsolutePath());
    }

    @Test
    void testSchemaValidateAcceptsValidMigrationEnvelope() throws Exception {
        String json = """
            {"$schema":"https://opencrawling.org/schemas/v1/ois-document.json","id":"DOC-1","action":"UPSERT",
             "contentRef":{"key":"legal/report.pdf","claimCheckUri":"s3://claims/report.pdf","filename":"report.pdf",
               "mimeType":"application/pdf","contentLength":1048576,
               "checksumSha256":"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"},
             "security":{"inheritanceEnabled":true,"permissions":[
               {"identity":"ROLE_LEGAL","identityType":"ROLE","access":"READ"},
               {"principalId":"jdoe","principalType":"USER","accessType":"READ_WRITE"}]}}
            """;
        assertEquals(0, validate(json));
        assertTrue(outContent.toString().contains("OIS Migration Mode document payload detected"));
    }

    @Test
    void testSchemaValidateRejectsBrokenMigrationEnvelope() throws Exception {
        String json = """
            {"id":"DOC-2","action":"UPSERT",
             "contentRef":{"key":"x","mimeType":"","contentLength":-1,"checksumSha256":"abc"},
             "security":{"inheritanceEnabled":"yes","permissions":[{"identity":"","access":"READ"}]}}
            """;
        assertEquals(1, validate(json));
        String out = outContent.toString();
        assertTrue(out.contains("checksumSha256"));
        assertTrue(out.contains("contentLength"));
        assertTrue(out.contains("mimeType"));
        assertTrue(out.contains("inheritanceEnabled"));
        assertTrue(out.contains("permissions[0]"));
    }

    @Test
    void testSchemaValidateRejectsTombstoneWithContentRef() throws Exception {
        String json = """
            {"id":"DOC-3","action":"DELETE","contentRef":{"key":"x"}}
            """;
        assertEquals(1, validate(json));
        assertTrue(outContent.toString().contains("DELETE tombstones must not carry"));
    }

    @Test
    void testSchemaValidateRejectsUnknownJobPipelineMode() throws Exception {
        assertEquals(1, validate("{\"name\":\"J\",\"repositoryConnector\":\"FileSystem\",\"pipelineMode\":\"migraton\"}"));
        assertEquals(0, validate("{\"name\":\"J\",\"repositoryConnector\":\"FileSystem\",\"pipelineMode\":\"migration\"}"));
    }

    @Test
    void testJobStartRejectsInvalidModeBeforeContactingServer() {
        java.io.PrintStream originalErr = System.err;
        ByteArrayOutputStream errContent = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errContent));
        try {
            int exitCode = new CommandLine(new OpenCrawlingCliCommand())
                    .execute("job", "start", "--name", "x", "--mode", "migraton", "--url", "http://127.0.0.1:1");
            assertEquals(2, exitCode);
            assertTrue(errContent.toString().contains("Invalid --mode"));
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    void testReportCommandHelp() {
        CommandLine cmd = new CommandLine(new OpenCrawlingCliCommand());
        int exitCode = cmd.execute("report", "--help");
        assertEquals(0, exitCode);
        assertTrue(outContent.toString().contains("Audit reporting"));
        assertTrue(outContent.toString().contains("ledger"));
        assertTrue(outContent.toString().contains("export"));
        assertTrue(outContent.toString().contains("query"));
    }

    @Test
    void testReportGetRequiresArguments() {
        java.io.PrintStream originalErr = System.err;
        ByteArrayOutputStream errContent = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errContent));
        try {
            int exitCode = new CommandLine(new OpenCrawlingCliCommand())
                    .execute("report", "get");
            assertEquals(2, exitCode);
            assertTrue(errContent.toString().contains("Both Job ID and Execution ID must be specified"));
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    void testReportQueryRequiresSql() {
        java.io.PrintStream originalErr = System.err;
        ByteArrayOutputStream errContent = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errContent));
        try {
            int exitCode = new CommandLine(new OpenCrawlingCliCommand())
                    .execute("report", "query");
            assertEquals(2, exitCode);
            assertTrue(errContent.toString().contains("Please provide a SQL query"));
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    void testPushCommandHelp() {
        CommandLine cmd = new CommandLine(new OpenCrawlingCliCommand());
        int exitCode = cmd.execute("push", "--help");
        assertEquals(0, exitCode);
        String out = outContent.toString();
        assertTrue(out.contains("gRPC Push Repository Connector"));
        assertTrue(out.contains("ping"));
        assertTrue(out.contains("upsert"));
        assertTrue(out.contains("delete"));
        assertTrue(out.contains("stream"));
    }

    @Test
    void testPushCommandsAgainstEmbeddedGrpcServer() throws Exception {
        org.opencrawling.push.grpc.GrpcPushProperties props = new org.opencrawling.push.grpc.GrpcPushProperties();
        props.setEnabled(true);
        props.setPort(0);
        props.setToken("cli-push-secret");

        org.opencrawling.push.grpc.PushDocumentDispatcher dispatcher = request ->
                org.opencrawling.push.v1.PushDocumentResponse.newBuilder()
                        .setDocumentId(request.getDocument().getId())
                        .setStatus(org.opencrawling.push.v1.IngestionStatus.ACCEPTED)
                        .setMessage("Queued by test dispatcher")
                        .setProcessedAt(System.currentTimeMillis())
                        .build();

        org.opencrawling.push.grpc.GrpcPushServerManager serverManager =
                new org.opencrawling.push.grpc.GrpcPushServerManager(
                        props,
                        new org.opencrawling.push.grpc.OisPushServiceImpl(dispatcher),
                        new org.opencrawling.push.grpc.GrpcSecurityInterceptor(props),
                        new org.opencrawling.push.grpc.OpenTelemetryServerInterceptor());
        serverManager.start();
        try {
            String portStr = String.valueOf(serverManager.getPort());

            // 1. oc push ping
            outContent.reset();
            assertEquals(0, new CommandLine(new OpenCrawlingCliCommand())
                    .execute("push", "ping", "--host", "127.0.0.1", "--port", portStr, "--format", "json"));
            assertTrue(outContent.toString().contains("\"status\":\"UP\""));

            // 2. oc push upsert without token -> rejected (exit code 1)
            outContent.reset();
            assertEquals(1, new CommandLine(new OpenCrawlingCliCommand())
                    .execute("push", "upsert", "--host", "127.0.0.1", "--port", portStr,
                            "--id", "doc://unauth", "--text", "unauthenticated", "--format", "json"));
            assertTrue(outContent.toString().contains("\"grpcStatus\":\"UNAUTHENTICATED\""));

            // 3. oc push upsert with valid token and inline text
            outContent.reset();
            assertEquals(0, new CommandLine(new OpenCrawlingCliCommand())
                    .execute("push", "upsert", "--host", "127.0.0.1", "--port", portStr,
                            "--token", "cli-push-secret",
                            "--job-id", "1",
                            "--id", "doc://cli-test-1",
                            "--text", "OpenCrawling CLI gRPC Push test content",
                            "--acl-allow", "ROLE_USER,GROUP_ENG",
                            "--metadata", "title=CLI Test",
                            "--format", "json"));
            assertTrue(outContent.toString().contains("\"accepted\":true"));

            // 4. oc push upsert with --file
            java.io.File tempDoc = java.io.File.createTempFile("oc-cli-push-doc", ".txt");
            tempDoc.deleteOnExit();
            java.nio.file.Files.writeString(tempDoc.toPath(), "File payload via oc push upsert");
            outContent.reset();
            assertEquals(0, new CommandLine(new OpenCrawlingCliCommand())
                    .execute("push", "upsert", "--host", "127.0.0.1", "--port", portStr,
                            "--token", "cli-push-secret",
                            "--id", "doc://cli-file-1",
                            "--file", tempDoc.getAbsolutePath()));
            assertTrue(outContent.toString().contains("Document accepted"));

            // 5. oc push delete tombstone
            outContent.reset();
            assertEquals(0, new CommandLine(new OpenCrawlingCliCommand())
                    .execute("push", "delete", "--host", "127.0.0.1", "--port", portStr,
                            "--token", "cli-push-secret",
                            "--job-id", "1",
                            "--id", "doc://cli-test-1",
                            "--format", "json"));
            assertTrue(outContent.toString().contains("\"accepted\":true"));

            // 6. oc push stream (client streaming)
            outContent.reset();
            assertEquals(0, new CommandLine(new OpenCrawlingCliCommand())
                    .execute("push", "stream", "--host", "127.0.0.1", "--port", portStr,
                            "--token", "cli-push-secret",
                            "--doc-ids", "doc://s1,doc://s2",
                            "--text", "Streamed via oc-cli",
                            "--format", "json"));
            assertTrue(outContent.toString().contains("\"totalAccepted\":2"));

            // 7. oc push stream --bidi (bidirectional streaming)
            outContent.reset();
            assertEquals(0, new CommandLine(new OpenCrawlingCliCommand())
                    .execute("push", "stream", "--bidi", "--host", "127.0.0.1", "--port", portStr,
                            "--token", "cli-push-secret",
                            "--doc-ids", "doc://b1,doc://b2",
                            "--text", "Bidi streamed via oc-cli",
                            "--format", "json"));
            assertTrue(outContent.toString().contains("\"accepted\":true"));
        } finally {
            serverManager.stop();
        }
    }
}

