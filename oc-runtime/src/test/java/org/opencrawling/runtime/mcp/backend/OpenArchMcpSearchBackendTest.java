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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.openarch.output.client.OpenArchClient;
import org.opencrawling.runtime.mcp.McpVectorServer;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class OpenArchMcpSearchBackendTest {

    private OpenArchClient mockClient;
    private EmbeddingModel mockEmbeddingModel;
    private OpenArchMcpSearchBackend backend;
    private ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    public void setup() {
        mockClient = mock(OpenArchClient.class);
        mockEmbeddingModel = mock(EmbeddingModel.class);
        when(mockEmbeddingModel.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f, 0.3f});
        backend = new OpenArchMcpSearchBackend(mockClient, mockEmbeddingModel);
    }

    @Test
    public void testBackendType() {
        assertEquals("openarch", backend.backendType());
    }

    @Test
    public void testSecureVectorSearch() throws Exception {
        ObjectNode response = mapper.createObjectNode();
        ArrayNode embeddings = mapper.createArrayNode();

        ObjectNode emb1 = mapper.createObjectNode();
        emb1.put("id", "emb-1");
        emb1.put("docId", "doc-100");
        emb1.put("text", "Federal acquisition regulations policy documentation");
        emb1.put("score", 0.95);
        embeddings.add(emb1);

        response.set("embeddings", embeddings);

        when(mockClient.queryEmbedding(anyString(), anyString(), anyList(), anyString(), isNull(), anyInt(), anyLong()))
                .thenReturn(response);

        List<McpVectorServer.DocumentSearchResult> results = backend.secureVectorSearch(
                "acquisition regulations",
                "analyst_user",
                List.of("analysts"),
                5,
                0.7,
                1024
        );

        assertNotNull(results);
        assertEquals(1, results.size());
        McpVectorServer.DocumentSearchResult res = results.get(0);
        assertEquals("emb-1", res.id());
        assertEquals("openarch://default/doc-100", res.uri());
        assertEquals("Federal acquisition regulations policy documentation", res.content());
        assertEquals(0.95, res.score(), 0.001);
    }

    @Test
    public void testGetDocumentContent() throws Exception {
        ObjectNode docNode = mapper.createObjectNode();
        docNode.put("id", "doc-100");
        docNode.put("sys_name", "Contract_Agreement.pdf");
        docNode.put("sys_title", "Master Services Agreement 2026");
        docNode.put("sys_description", "Standard commercial enterprise contract terms.");

        when(mockClient.getDocument(eq("default"), eq("doc-100"))).thenReturn(docNode);

        McpVectorServer.DocumentDetailsResult details = backend.getDocumentContent(
                "openarch://default/doc-100",
                "analyst_user",
                List.of("legal"),
                1024
        );

        assertNotNull(details);
        assertEquals("doc-100", details.id());
        assertEquals("openarch://default/doc-100", details.uri());
        assertTrue(details.content().contains("Master Services Agreement 2026"));
        assertTrue(details.content().contains("Standard commercial enterprise contract terms."));
    }

    @Test
    public void testListAccessibleSources() throws Exception {
        ObjectNode queryResult = mapper.createObjectNode();
        ArrayNode docs = mapper.createArrayNode();

        ObjectNode d1 = mapper.createObjectNode();
        d1.put("id", "doc-1");
        d1.put("sys_name", "Policy.docx");
        docs.add(d1);

        queryResult.set("documents", docs);

        when(mockClient.query(eq("default"), anyString(), anyInt(), anyLong(), isNull())).thenReturn(queryResult);

        List<Map<String, Object>> sources = backend.listAccessibleSources("user1", List.of(), 1024);
        assertNotNull(sources);
        assertEquals(1, sources.size());
        assertEquals("doc-1", sources.get(0).get("id"));
        assertEquals("Policy.docx", sources.get(0).get("name"));
        assertEquals("openarch://default/doc-1", sources.get(0).get("uri"));
    }
}
