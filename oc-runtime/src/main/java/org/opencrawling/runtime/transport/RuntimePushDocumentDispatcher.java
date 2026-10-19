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

import org.opencrawling.core.claimcheck.ClaimCheckStore;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.push.grpc.GrpcPushProperties;
import org.opencrawling.push.grpc.OisDocumentTranslator;
import org.opencrawling.push.grpc.PushDocumentDispatcher;
import org.opencrawling.push.v1.*;
import org.opencrawling.runtime.api.JobController;
import org.opencrawling.runtime.api.JobController.JobDTO;
import org.opencrawling.runtime.orchestrator.JobOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/**
 * OpenCrawling Runtime implementation of {@link PushDocumentDispatcher}.
 * Bridges the gRPC Push ingestion service with {@link JobController} validation and
 * {@link JobOrchestrator} Kafka event bus dispatch.
 */
@Service
public class RuntimePushDocumentDispatcher implements PushDocumentDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RuntimePushDocumentDispatcher.class);

    private final JobController jobController;
    private final JobOrchestrator jobOrchestrator;
    private final ClaimCheckStore claimCheckStore;
    private final GrpcPushProperties properties;

    @Autowired
    public RuntimePushDocumentDispatcher(
            JobController jobController,
            JobOrchestrator jobOrchestrator,
            ClaimCheckStore claimCheckStore,
            GrpcPushProperties properties) {
        this.jobController = jobController;
        this.jobOrchestrator = jobOrchestrator;
        this.claimCheckStore = claimCheckStore;
        this.properties = properties;
    }

    @Override
    public PushDocumentResponse dispatch(PushDocumentRequest request) {
        if (request == null) {
            return PushDocumentResponse.newBuilder()
                    .setStatus(IngestionStatus.REJECTED_INVALID_PAYLOAD)
                    .setMessage("PushDocumentRequest payload cannot be null")
                    .setProcessedAt(System.currentTimeMillis())
                    .build();
        }

        String jobId = request.getJobId();
        if (jobId == null || jobId.isBlank()) {
            return PushDocumentResponse.newBuilder()
                    .setStatus(IngestionStatus.REJECTED_INVALID_PAYLOAD)
                    .setMessage("Target job_id is required")
                    .setProcessedAt(System.currentTimeMillis())
                    .build();
        }

        JobDTO job;
        try {
            ResponseEntity<JobDTO> response = jobController.getJob(jobId);
            job = response != null ? response.getBody() : null;
        } catch (Exception e) {
            log.warn("Failed to retrieve job {} during gRPC push validation: {}", jobId, e.getMessage());
            job = null;
        }

        if (job == null) {
            String docId = request.getDocument() != null ? request.getDocument().getId() : "";
            return PushDocumentResponse.newBuilder()
                    .setDocumentId(docId)
                    .setStatus(IngestionStatus.REJECTED_JOB_NOT_FOUND)
                    .setMessage("Target job not found: " + jobId)
                    .setProcessedAt(System.currentTimeMillis())
                    .build();
        }

        if (jobController.resolvePipelineMode(job).isMigration()) {
            String docId = request.getDocument() != null ? request.getDocument().getId() : "";
            return PushDocumentResponse.newBuilder()
                    .setDocumentId(docId)
                    .setStatus(IngestionStatus.REJECTED_INVALID_PAYLOAD)
                    .setMessage("Target job runs in migration mode; OIS push ingestion supports RAG jobs only")
                    .setProcessedAt(System.currentTimeMillis())
                    .build();
        }

        OisDocumentPayload docPayload = request.getDocument();
        if (docPayload == null || docPayload.getId() == null || docPayload.getId().isBlank()) {
            return PushDocumentResponse.newBuilder()
                    .setStatus(IngestionStatus.REJECTED_INVALID_PAYLOAD)
                    .setMessage("Document ID is required in OisDocumentPayload")
                    .setProcessedAt(System.currentTimeMillis())
                    .build();
        }

        try {
            int thresholdKb = properties != null ? properties.getInlineBinaryThresholdKb() : 512;
            RepositoryDocument doc = OisDocumentTranslator.translate(
                    docPayload,
                    request.getOptions(),
                    claimCheckStore,
                    thresholdKb
            );

            jobOrchestrator.publishDocument(doc, job.transformationConnector());

            return PushDocumentResponse.newBuilder()
                    .setDocumentId(doc.id())
                    .setStatus(IngestionStatus.ACCEPTED)
                    .setMessage("Document accepted and published to Kafka ingestion pipeline")
                    .setProcessedAt(System.currentTimeMillis())
                    .build();

        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("Failed to publish pushed OIS document {} for job {}: {}", docPayload.getId(), jobId, e.getMessage(), e);
            return PushDocumentResponse.newBuilder()
                    .setDocumentId(docPayload.getId())
                    .setStatus(IngestionStatus.ERROR_INTERNAL)
                    .setMessage("Ingestion error: " + e.getMessage())
                    .setProcessedAt(System.currentTimeMillis())
                    .build();
        }
    }
}
