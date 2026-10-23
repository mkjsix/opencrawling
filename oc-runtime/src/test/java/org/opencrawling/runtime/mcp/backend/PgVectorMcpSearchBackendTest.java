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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

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

    @Test
    void testSecureVectorSearch_multiReaderDocumentFoundForEveryReader() {
        when(embeddingModel.embed("budget plan")).thenReturn(new float[]{0.1f, 0.2f});

        Document multiReaderDoc = new Document("doc-123", "Shared budget plan for engineering and finance.", Map.of(
                "uri", "http://repro.example/two-readers.html",
                "acl", "engineering,finance",
                "security_allowed_read", List.of("engineering", "finance")
        ));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(multiReaderDoc));

        // 1. Engineering caller finds the document
        List<McpVectorServer.DocumentSearchResult> engResults = backend.secureVectorSearch(
                "budget plan", "alice@enterprise.com", List.of("engineering"), 5, 0.0, null
        );
        assertThat(engResults).hasSize(1);
        assertThat(engResults.get(0).id()).isEqualTo("doc-123");

        // 2. Finance caller ALSO finds the document (issue #163 fix)
        List<McpVectorServer.DocumentSearchResult> finResults = backend.secureVectorSearch(
                "budget plan", "bob@enterprise.com", List.of("finance"), 5, 0.0, null
        );
        assertThat(finResults).hasSize(1);
        assertThat(finResults.get(0).id()).isEqualTo("doc-123");

        // 3. HR caller does NOT get the document
        List<McpVectorServer.DocumentSearchResult> hrResults = backend.secureVectorSearch(
                "budget plan", "carol@enterprise.com", List.of("hr"), 5, 0.0, null
        );
        assertThat(hrResults).isEmpty();
    }

    @Test
    void testSecureVectorSearch_filterExpressionIncludesSecurityAllowedReadAndAcl() {
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f});
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        backend.secureVectorSearch("query", "user1", List.of("finance", "engineering"), 5, 0.0, null);

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());

        SearchRequest req = captor.getValue();
        assertThat(req.getTopK()).isEqualTo(5);
        assertThat(req.getFilterExpression()).isNotNull();
        String exprStr = req.getFilterExpression().toString();
        assertThat(exprStr).contains("security_allowed_read");
        assertThat(exprStr).contains("acl");
        assertThat(exprStr).contains("finance");
        assertThat(exprStr).contains("engineering");
        assertThat(exprStr).contains("user1");
        assertThat(exprStr).contains("public");
    }

    @Test
    void testEmptyPermissionsDocumentExcludedFromAllToolsForOutsider() {
        // A document pushed with "permissions": [] has:
        // acl="", security_allowed_read=[], and security.permissions=[]
        String docUri = "http://repro.example/nobody-access.html";
        Map<String, Object> emptyPermsMeta = Map.of(
                "uri", docUri,
                "acl", "",
                "security_allowed_read", List.of(),
                "security", Map.of("permissions", List.of())
        );
        Document emptyPermsDoc = new Document("doc-empty-perms", "Confidential text for nobody.", emptyPermsMeta);

        // 1. secureVectorSearch as outsider -> excluded by McpAclEvaluator even if returned by similaritySearch
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(emptyPermsDoc));

        List<McpVectorServer.DocumentSearchResult> searchResults = backend.secureVectorSearch(
                "confidential", "outsider", List.of(), 5, 0.0, null
        );
        assertThat(searchResults).isEmpty();

        // 2. getDocumentContent as outsider -> throws NoSuchElementException (access denied)
        assertThatThrownBy(() -> backend.getDocumentContent(docUri, "outsider", List.of(), null))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Document not found or access denied.");

        // 3. listAccessibleSources as outsider -> excluded from results
        List<Map<String, Object>> sources = backend.listAccessibleSources("outsider", List.of(), null);
        assertThat(sources).isEmpty();
    }
}
