/*
 * Copyright © ${year} the original author or authors (piergiorgio@apache.org)
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
package org.opencrawling.runtime.transport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opencrawling.core.claimcheck.ClaimCheckStore;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.push.grpc.GrpcPushProperties;
import org.opencrawling.push.v1.IngestionStatus;
import org.opencrawling.push.v1.OisDocumentPayload;
import org.opencrawling.push.v1.PushDocumentRequest;
import org.opencrawling.push.v1.PushDocumentResponse;
import org.opencrawling.runtime.api.JobController;
import org.opencrawling.runtime.api.JobController.JobDTO;
import org.opencrawling.runtime.orchestrator.JobOrchestrator;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RuntimePushDocumentDispatcherTest {

    private JobController jobController;
    private JobOrchestrator jobOrchestrator;
    private ClaimCheckStore claimCheckStore;
    private GrpcPushProperties properties;
    private RuntimePushDocumentDispatcher dispatcher;

    private JobDTO activeJob;

    @BeforeEach
    void setUp() {
        jobController = mock(JobController.class);
        jobOrchestrator = mock(JobOrchestrator.class);
        claimCheckStore = mock(ClaimCheckStore.class);
        properties = new GrpcPushProperties();
        dispatcher = new RuntimePushDocumentDispatcher(jobController, jobOrchestrator, claimCheckStore, properties);

        activeJob = new JobDTO(
                "job-1", "Test Job", "Grpc_Push_Connector", "PGVector_Output",
                null, "push://incoming", "RUNNING", "SCAN", 0L, null, "Ollama_Embedding_Default", null
        );
    }

    @Test
    void testDispatchRejectedWhenJobNotFound() {
        when(jobController.getJob("unknown-job")).thenReturn(ResponseEntity.notFound().build());

        PushDocumentRequest request = PushDocumentRequest.newBuilder()
                .setJobId("unknown-job")
                .setDocument(OisDocumentPayload.newBuilder().setId("doc-1").setAction(org.opencrawling.push.v1.DocumentAction.UPSERT).build())
                .build();

        PushDocumentResponse response = dispatcher.dispatch(request);
        assertNotNull(response);
        assertEquals(IngestionStatus.REJECTED_JOB_NOT_FOUND, response.getStatus());
        assertTrue(response.getMessage().contains("Target job not found"));
    }

    @Test
    void testDispatchRejectedInMigrationMode() {
        when(jobController.getJob("job-1")).thenReturn(ResponseEntity.ok(activeJob));
        when(jobController.resolvePipelineMode(activeJob)).thenReturn(PipelineMode.MIGRATION);

        PushDocumentRequest request = PushDocumentRequest.newBuilder()
                .setJobId("job-1")
                .setDocument(OisDocumentPayload.newBuilder().setId("doc-1").setAction(org.opencrawling.push.v1.DocumentAction.UPSERT).build())
                .build();

        PushDocumentResponse response = dispatcher.dispatch(request);
        assertNotNull(response);
        assertEquals(IngestionStatus.REJECTED_INVALID_PAYLOAD, response.getStatus());
        assertTrue(response.getMessage().contains("migration mode"));
    }

    @Test
    void testDispatchAcceptedForUpsert() throws Exception {
        when(jobController.getJob("job-1")).thenReturn(ResponseEntity.ok(activeJob));
        when(jobController.resolvePipelineMode(activeJob)).thenReturn(PipelineMode.RAG);

        PushDocumentRequest request = PushDocumentRequest.newBuilder()
                .setJobId("job-1")
                .setDocument(OisDocumentPayload.newBuilder()
                        .setId("contract-2026.pdf")
                        .setAction(org.opencrawling.push.v1.DocumentAction.UPSERT)
                        .setMimeType("application/pdf")
                        .setTextContent("Contract agreement details...")
                        .putMetadata("department", "Legal")
                        .build())
                .build();

        PushDocumentResponse response = dispatcher.dispatch(request);
        assertNotNull(response);
        assertEquals(IngestionStatus.ACCEPTED, response.getStatus());
        assertEquals("contract-2026.pdf", response.getDocumentId());

        ArgumentCaptor<RepositoryDocument> captor = ArgumentCaptor.forClass(RepositoryDocument.class);
        verify(jobOrchestrator).publishDocument(captor.capture(), eq("Ollama_Embedding_Default"));

        RepositoryDocument published = captor.getValue();
        assertEquals("contract-2026.pdf", published.id());
        assertEquals(DocumentAction.UPSERT, published.action());
        assertEquals(java.util.List.of("Legal"), published.metadata().get("department"));
    }

    @Test
    void testDispatchAcceptedForDeleteTombstone() throws Exception {
        when(jobController.getJob("job-1")).thenReturn(ResponseEntity.ok(activeJob));
        when(jobController.resolvePipelineMode(activeJob)).thenReturn(PipelineMode.RAG);

        PushDocumentRequest request = PushDocumentRequest.newBuilder()
                .setJobId("job-1")
                .setDocument(OisDocumentPayload.newBuilder()
                        .setId("contract-2026.pdf")
                        .setAction(org.opencrawling.push.v1.DocumentAction.DELETE)
                        .build())
                .build();

        PushDocumentResponse response = dispatcher.dispatch(request);
        assertEquals(IngestionStatus.ACCEPTED, response.getStatus());

        ArgumentCaptor<RepositoryDocument> captor = ArgumentCaptor.forClass(RepositoryDocument.class);
        verify(jobOrchestrator).publishDocument(captor.capture(), eq("Ollama_Embedding_Default"));

        RepositoryDocument published = captor.getValue();
        assertEquals("contract-2026.pdf", published.id());
        assertEquals(DocumentAction.DELETE, published.action());
        assertNull(published.contentStream());
    }

    @Test
    void testDispatchReturnsInternalErrorWhenPublishFails() throws Exception {
        when(jobController.getJob("job-1")).thenReturn(ResponseEntity.ok(activeJob));
        when(jobController.resolvePipelineMode(activeJob)).thenReturn(PipelineMode.RAG);
        doThrow(new RuntimeException("Kafka producer unavailable")).when(jobOrchestrator).publishDocument(any(), any());

        PushDocumentRequest request = PushDocumentRequest.newBuilder()
                .setJobId("job-1")
                .setDocument(OisDocumentPayload.newBuilder()
                        .setId("doc-fail")
                        .setAction(org.opencrawling.push.v1.DocumentAction.UPSERT)
                        .setTextContent("Content")
                        .build())
                .build();

        PushDocumentResponse response = dispatcher.dispatch(request);
        assertEquals(IngestionStatus.ERROR_INTERNAL, response.getStatus());
        assertTrue(response.getMessage().contains("Kafka producer unavailable"));
    }
}
