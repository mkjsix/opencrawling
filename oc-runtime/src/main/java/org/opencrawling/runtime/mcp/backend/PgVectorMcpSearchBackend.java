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
package org.opencrawling.runtime.mcp.backend;

import org.opencrawling.runtime.mcp.McpAclEvaluator;
import org.opencrawling.runtime.mcp.McpVectorSearchBackend;
import org.opencrawling.runtime.mcp.McpVectorServer;
import org.opencrawling.vector.config.PrecomputedEmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "pgvector", matchIfMissing = true)
@ConditionalOnBean(VectorStore.class)
public class PgVectorMcpSearchBackend implements McpVectorSearchBackend {

    private static final Logger log = LoggerFactory.getLogger(PgVectorMcpSearchBackend.class);

    private final VectorStore vectorStore;
    private final VectorStore vectorStore384;
    private final VectorStore vectorStore768;
    private final VectorStore vectorStore1024;
    private final EmbeddingModel embeddingModel;

    @Autowired
    public PgVectorMcpSearchBackend(
            @Qualifier("vectorStore") VectorStore vectorStore,
            @Qualifier("vectorStore384") VectorStore vectorStore384,
            @Qualifier("vectorStore768") VectorStore vectorStore768,
            @Qualifier("vectorStore1024") VectorStore vectorStore1024,
            @Autowired(required = false) @Qualifier("ollamaEmbeddingModel") EmbeddingModel ollamaEmbeddingModel,
            @Autowired(required = false) EmbeddingModel defaultEmbeddingModel) {
        this.vectorStore = vectorStore;
        this.vectorStore384 = vectorStore384;
        this.vectorStore768 = vectorStore768;
        this.vectorStore1024 = vectorStore1024;
        this.embeddingModel = (ollamaEmbeddingModel != null) ? ollamaEmbeddingModel : defaultEmbeddingModel;
        log.info("Initialized PgVectorMcpSearchBackend.");
    }

    @Override
    public String backendType() {
        return "pgvector";
    }

    private VectorStore getVectorStore(Integer dimensions) {
        if (dimensions == null) return vectorStore;
        return switch (dimensions) {
            case 384 -> vectorStore384;
            case 768 -> vectorStore768;
            case 1024 -> vectorStore1024;
            default -> vectorStore;
        };
    }

    @Override
    public List<McpVectorServer.DocumentSearchResult> secureVectorSearch(
            String query,
            String userPrincipal,
            List<String> userRoles,
            int maxResults,
            double minScore,
            Integer dimensions
    ) {
        log.info("pgvector secure search. Query: '{}', Principal: '{}', Roles: {}, Dimensions: {}",
                query, userPrincipal, userRoles, dimensions);

        int limit = maxResults > 0 ? maxResults : 5;
        double threshold = minScore >= 0.0 ? minScore : 0.0;

        FilterExpressionBuilder b = new FilterExpressionBuilder();
        var securityExpression = b.eq("acl", "public");

        if (userPrincipal != null && !userPrincipal.trim().isBlank()) {
            securityExpression = b.or(securityExpression, b.eq("acl", userPrincipal.trim()));
        }

        if (userRoles != null && !userRoles.isEmpty()) {
            securityExpression = b.or(securityExpression, b.in("acl", userRoles));
        }

        SearchRequest searchRequest = SearchRequest.builder()
                .query(query)
                .filterExpression(securityExpression.build())
                .topK(limit)
                .similarityThreshold(threshold)
                .build();

        boolean precomputedSet = false;
        try {
            if (query != null && !query.isBlank() && embeddingModel != null && !(embeddingModel instanceof PrecomputedEmbeddingModel)) {
                try {
                    float[] vector = embeddingModel.embed(query);
                    if (vector != null && vector.length > 0) {
                        PrecomputedEmbeddingModel.setPrecomputedVector(query, vector);
                        precomputedSet = true;
                    }
                } catch (Exception e) {
                    log.warn("Failed to generate query embedding via active model: {}", e.getMessage());
                }
            }

            List<Document> docs = getVectorStore(dimensions).similaritySearch(searchRequest);
            return docs.stream()
                    .filter(doc -> McpAclEvaluator.isAccessible(doc.getMetadata(), userPrincipal, userRoles))
                    .limit(limit)
                    .map(doc -> new McpVectorServer.DocumentSearchResult(
                            doc.getId(),
                            (String) doc.getMetadata().getOrDefault("uri", ""),
                            doc.getText(),
                            doc.getMetadata(),
                            (String) doc.getMetadata().getOrDefault("acl", "public"),
                            doc.getScore() != null ? doc.getScore() : 0.0
                    ))
                    .toList();
        } catch (Exception e) {
            log.error("Failed to perform secure vector search in pgvector: {}", e.getMessage(), e);
            throw new RuntimeException("Error executing secure vector search in pgvector", e);
        } finally {
            if (precomputedSet) {
                PrecomputedEmbeddingModel.clear();
            }
        }
    }

    @Override
    public McpVectorServer.DocumentDetailsResult getDocumentContent(
            String documentUri,
            String userPrincipal,
            List<String> userRoles,
            Integer dimensions
    ) {
        log.info("pgvector get document content. URI: '{}', Principal: '{}', Dimensions: {}", documentUri, userPrincipal, dimensions);

        FilterExpressionBuilder b = new FilterExpressionBuilder();
        var securityExpression = b.eq("acl", "public");
        if (userPrincipal != null && !userPrincipal.trim().isBlank()) {
            securityExpression = b.or(securityExpression, b.eq("acl", userPrincipal.trim()));
        }
        if (userRoles != null && !userRoles.isEmpty()) {
            securityExpression = b.or(securityExpression, b.in("acl", userRoles));
        }

        var finalExpression = b.and(b.eq("uri", documentUri), securityExpression).build();
        SearchRequest searchRequest = SearchRequest.builder()
                .query("")
                .filterExpression(finalExpression)
                .topK(20)
                .build();

        try {
            List<Document> docs = getVectorStore(dimensions).similaritySearch(searchRequest);
            return docs.stream()
                    .filter(doc -> McpAclEvaluator.isAccessible(doc.getMetadata(), userPrincipal, userRoles))
                    .findFirst()
                    .map(doc -> new McpVectorServer.DocumentDetailsResult(
                            doc.getId(),
                            (String) doc.getMetadata().getOrDefault("uri", ""),
                            doc.getText(),
                            doc.getMetadata(),
                            (String) doc.getMetadata().getOrDefault("acl", "public")
                    ))
                    .orElseThrow(() -> new NoSuchElementException("Document not found or access denied."));
        } catch (NoSuchElementException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to retrieve document details in pgvector: {}", e.getMessage(), e);
            throw new RuntimeException("Error retrieving document details from pgvector", e);
        }
    }

    @Override
    public List<Map<String, Object>> listAccessibleSources(
            String userPrincipal,
            List<String> userRoles,
            Integer dimensions
    ) {
        log.info("pgvector list accessible sources. Principal: '{}', Dimensions: {}", userPrincipal, dimensions);

        SearchRequest searchRequest = SearchRequest.builder()
                .query("")
                .topK(200)
                .build();

        try {
            List<Document> docs = getVectorStore(dimensions).similaritySearch(searchRequest);
            return docs.stream()
                    .filter(doc -> McpAclEvaluator.isAccessible(doc.getMetadata(), userPrincipal, userRoles))
                    .map(doc -> {
                        Map<String, Object> summary = new HashMap<>();
                        summary.put("id", doc.getId());
                        summary.put("uri", doc.getMetadata().getOrDefault("uri", ""));
                        summary.put("acl", doc.getMetadata().getOrDefault("acl", "public"));
                        summary.put("security", doc.getMetadata().get("security"));
                        summary.put("lastModified", doc.getMetadata().getOrDefault("lastModified", ""));
                        return summary;
                    })
                    .distinct()
                    .toList();
        } catch (Exception e) {
            log.error("Failed to list accessible sources in pgvector: {}", e.getMessage(), e);
            throw new RuntimeException("Error listing accessible sources from pgvector", e);
        }
    }
}
