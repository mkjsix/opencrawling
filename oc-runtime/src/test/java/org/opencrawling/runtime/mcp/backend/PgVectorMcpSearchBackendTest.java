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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.runtime.mcp.McpVectorServer;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PgVectorMcpSearchBackendTest {

    private VectorStore vectorStore;
    private EmbeddingModel embeddingModel;
    private PgVectorMcpSearchBackend backend;

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        embeddingModel = mock(EmbeddingModel.class);
        backend = new PgVectorMcpSearchBackend(
                vectorStore, vectorStore, vectorStore, vectorStore,
                embeddingModel, null
        );
    }

    @Test
    void testBackendType() {
        assertThat(backend.backendType()).isEqualTo("pgvector");
    }

    @Test
    void testSecureVectorSearch_embedsQueryAndFiltersAcl() {
        when(embeddingModel.embed("cloud architecture")).thenReturn(new float[]{0.1f, 0.2f});

        List<Document> docs = List.of(
                new Document("1", "Architecture Guide", Map.of("uri", "file:///arch.txt", "acl", "engineering")),
                new Document("2", "Secret Document", Map.of("uri", "file:///secret.txt", "acl", "executive"))
        );
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(docs);

        List<McpVectorServer.DocumentSearchResult> results = backend.secureVectorSearch(
                "cloud architecture", "john@enterprise.com", List.of("engineering"), 5, 0.0, null
        );

        verify(embeddingModel).embed("cloud architecture");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo("1");
        assertThat(results.get(0).acl()).isEqualTo("engineering");
    }

    @Test
    void testGetDocumentContent_success() {
        Document doc = new Document("1", "Architecture Guide", Map.of("uri", "file:///arch.txt", "acl", "public"));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));

        McpVectorServer.DocumentDetailsResult result = backend.getDocumentContent("file:///arch.txt", "john", List.of(), null);
        assertThat(result.id()).isEqualTo("1");
        assertThat(result.uri()).isEqualTo("file:///arch.txt");
    }

    @Test
    void testGetDocumentContent_deniedThrows() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> backend.getDocumentContent("file:///secret.txt", "john", List.of(), null))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void testListAccessibleSources() {
        List<Document> docs = List.of(
                new Document("1", "Public Doc", Map.of("uri", "file:///public.txt", "acl", "public")),
                new Document("2", "Private Doc", Map.of("uri", "file:///private.txt", "acl", "private"))
        );
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(docs);

        List<Map<String, Object>> sources = backend.listAccessibleSources("john", List.of(), null);
        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).get("uri")).isEqualTo("file:///public.txt");
    }
}
