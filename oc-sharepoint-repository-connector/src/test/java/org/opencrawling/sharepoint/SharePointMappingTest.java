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
package org.opencrawling.sharepoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.security.PermissionRule;
import org.springframework.mock.env.MockEnvironment;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Settings, principal tokens, permission mapping, list column values and ids. */
class SharePointMappingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SITE = "contoso.sharepoint.com,site,web";

    private static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    private static SharePointSecurityMapper mapper(Map<String, String> config) {
        SharePointProperties s = SharePointProperties.fromConfiguration(config);
        return new SharePointSecurityMapper(s, new SharePointPrincipals(s, SITE));
    }

    private static List<String> identities(SharePointSecurityMapper.Mapped mapped) {
        return mapped.security().permissions().stream().map(PermissionRule::identity).toList();
    }

    // ------------------------------------------------------------------ settings

    @Test
    void defaultsFollowTheIssue() {
        SharePointProperties s = SharePointProperties.fromConfiguration(Map.of());
        assertEquals(SharePointProperties.CrawlMode.DELTA, s.crawlMode());
        assertEquals("https://graph.microsoft.com/v1.0", s.graphUrl());
        assertEquals("https://login.microsoftonline.com", s.authorityUrl());
        assertEquals(52_428_800L, s.maxFileSizeBytes());
        assertEquals(100, s.batchSize());
        assertEquals(5, s.maxRetries());
        assertTrue(s.includeContentStream() && s.includeCustomFields() && s.includeAcls() && s.incremental());
        assertEquals("sharepoint_field_", s.fieldPrefix());
        assertEquals("", s.everyoneIdentity(), "broad claims grant nothing by default");
    }

    @Test
    void springPropertiesUseTheIssuesKeys() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.opencrawling.connector.sharepoint.tenant-id", "72f988bf")
                .withProperty("spring.opencrawling.connector.sharepoint.client-id", "app")
                .withProperty("spring.opencrawling.connector.sharepoint.client-secret", "shh")
                .withProperty("spring.opencrawling.connector.sharepoint.site-url", "https://contoso.sharepoint.com/sites/engineering/")
                .withProperty("spring.opencrawling.connector.sharepoint.drive-name", "Documents")
                .withProperty("spring.opencrawling.connector.sharepoint.drive-names[0]", "Contracts")
                .withProperty("spring.opencrawling.connector.sharepoint.crawl-mode", "tree")
                .withProperty("spring.opencrawling.connector.sharepoint.root-folder-path", "/Specs")
                .withProperty("spring.opencrawling.connector.sharepoint.initial-backoff-ms", "250")
                .withProperty("spring.opencrawling.connector.sharepoint.everyone-except-external-identity", "employees");
        SharePointProperties s = SharePointProperties.fromEnvironment(env);
        assertEquals("72f988bf", s.tenantId());
        assertEquals("contoso.sharepoint.com", s.hostname());
        assertEquals("/sites/engineering", s.sitePath());
        assertEquals(List.of("Contracts", "Documents"), s.driveNames());
        assertEquals(SharePointProperties.CrawlMode.TREE, s.crawlMode());
        assertEquals("/Specs", s.rootFolderPath());
        assertEquals(250, s.initialBackoff().toMillis());
        assertEquals("employees", s.everyoneExceptExternalIdentity());
        assertTrue(s.validate().isEmpty(), s.validate().toString());
        assertFalse(s.toString().contains("shh"));
    }

    @Test
    void validationNamesEveryProblem() {
        String all = String.join(" ", SharePointProperties.fromConfiguration(Map.of("siteUrl", "contoso", "graphUrl", "ftp://x")).validate());
        assertTrue(all.contains("tenant-id") && all.contains("client-id") && all.contains("contoso") && all.contains("graph-url"), all);
        assertEquals("", SharePointProperties.fromConfiguration(Map.of("siteUrl", "https://contoso.sharepoint.com")).sitePath());
    }

    // ------------------------------------------------------------------ principals

    @Test
    void claimsAreTurnedIntoStableTokens() throws Exception {
        SharePointProperties s = SharePointProperties.fromConfiguration(Map.of("everyoneIdentity", "public"));
        SharePointPrincipals p = new SharePointPrincipals(s, SITE);
        assertEquals("adele@contoso.com", p.siteUser(json("{\"loginName\":\"i:0#.f|membership|Adele@Contoso.com\"}")).orElseThrow().identity());
        assertEquals("0d1e-sec", p.siteUser(json("{\"loginName\":\"c:0t.c|tenant|0D1E-SEC\"}")).orElseThrow().identity());
        assertEquals("m365-grp", p.siteUser(json("{\"loginName\":\"c:0o.c|federateddirectoryclaimprovider|m365-grp\"}")).orElseThrow().identity());
        assertEquals("public", p.siteUser(json("{\"loginName\":\"c:0(.s|true\"}")).orElseThrow().identity());
        assertTrue(p.siteUser(json("{\"loginName\":\"c:0-.f|rolemanager|spo-grid-all-users/tid\"}")).isEmpty(),
                "everyone-except-external is not configured here");
        assertEquals("sharepoint-group:" + SITE + ":3", p.siteGroup(json("{\"id\":\"3\",\"displayName\":\"Owners\"}")).orElseThrow().identity());
        assertEquals("i:0#.w|contoso\\bob", p.siteUser(json("{\"loginName\":\"i:0#.w|CONTOSO\\\\bob\"}")).orElseThrow().identity(),
                "other claims are kept verbatim (lower case)");
    }

    @Test
    void userIdentityCanBeTheEntraObjectId() throws Exception {
        SharePointPrincipals p = new SharePointPrincipals(SharePointProperties.fromConfiguration(Map.of("userIdentity", "id")), SITE);
        assertEquals("u-123", p.user(json("{\"id\":\"U-123\",\"email\":\"x@contoso.com\"}")).orElseThrow().identity());
        SharePointPrincipals byMail = new SharePointPrincipals(SharePointProperties.fromConfiguration(Map.of()), SITE);
        assertEquals("u-123", byMail.user(json("{\"id\":\"U-123\"}")).orElseThrow().identity(), "no e-mail: falls back to the id");
    }

    // ------------------------------------------------------------------ permissions

    @Test
    void writeWinsOverReadAndDuplicatesCollapse() throws Exception {
        SharePointSecurityMapper.Mapped mapped = mapper(Map.of()).map(List.of(
                json("{\"roles\":[\"read\"],\"grantedToV2\":{\"group\":{\"id\":\"G1\"}}}"),
                json("{\"roles\":[\"write\"],\"grantedToV2\":{\"group\":{\"id\":\"g1\"}}}"),
                json("{\"roles\":[\"read\"],\"grantedToV2\":{\"group\":{\"id\":\"g1\"}}}")));
        assertEquals(1, mapped.security().permissions().size());
        assertEquals("write", mapped.security().permissions().getFirst().access());
        assertEquals("g1", mapped.acl());
    }

    @Test
    void deprecatedFieldsAreReadOnlyWithoutV2() throws Exception {
        SharePointSecurityMapper.Mapped v1 = mapper(Map.of()).map(List.of(
                json("{\"roles\":[\"read\"],\"grantedTo\":{\"user\":{\"email\":\"a@contoso.com\"}}}")));
        assertEquals(List.of("a@contoso.com"), identities(v1));
        SharePointSecurityMapper.Mapped both = mapper(Map.of()).map(List.of(json(
                "{\"roles\":[\"read\"],\"grantedTo\":{\"user\":{\"email\":\"old@contoso.com\"}},\"grantedToV2\":{\"user\":{\"email\":\"new@contoso.com\"}}}")));
        assertEquals(List.of("new@contoso.com"), identities(both));
    }

    @Test
    void nothingReadableMeansFallbackOrNobody() throws Exception {
        assertTrue(mapper(Map.of()).map(List.of()).security().permissions().isEmpty());
        assertTrue(mapper(Map.of()).map(List.of(json("{\"roles\":[\"read\"],\"link\":{\"scope\":\"existingAccess\"}}")))
                .security().permissions().isEmpty());
        SharePointSecurityMapper.Mapped fallback = mapper(Map.of("fallbackPrincipals", "group:records,user:auditor@contoso.com"))
                .map(List.of());
        assertEquals(List.of("records", "auditor@contoso.com"), identities(fallback));
        assertEquals("auditor@contoso.com,records", fallback.acl());
    }

    @Test
    void fingerprintIgnoresOrderButSeesChanges() throws Exception {
        JsonNode a = json("{\"roles\":[\"read\"],\"grantedToV2\":{\"group\":{\"id\":\"a\"}}}");
        JsonNode b = json("{\"roles\":[\"read\"],\"grantedToV2\":{\"group\":{\"id\":\"b\"}}}");
        JsonNode bWrite = json("{\"roles\":[\"write\"],\"grantedToV2\":{\"group\":{\"id\":\"b\"}}}");
        SharePointSecurityMapper m = mapper(Map.of());
        assertEquals(m.map(List.of(a, b)).fingerprint(), m.map(List.of(b, a)).fingerprint());
        assertNotEquals(m.map(List.of(a, b)).fingerprint(), m.map(List.of(a, bWrite)).fingerprint());
        assertNotEquals(m.map(List.of(a)).fingerprint(), m.map(List.of(a, b)).fingerprint());
    }

    // ------------------------------------------------------------------ documents and helpers

    @Test
    void columnValuesAreReducedToLabels() throws Exception {
        assertEquals(List.of("Internal"), SharePointDocumentBuilder.fieldValues(json("{\"Label\":\"Internal\",\"TermGuid\":\"t\"}")));
        assertEquals(List.of("A", "B"), SharePointDocumentBuilder.fieldValues(json("[{\"LookupValue\":\"A\"},{\"LookupValue\":\"B\"}]")));
        assertEquals(List.of("Site", "https://x"), SharePointDocumentBuilder.fieldValues(json("[{\"Description\":\"Site\",\"Url\":\"https://y\"},\"https://x\"]")));
        assertEquals(List.of("42"), SharePointDocumentBuilder.fieldValues(json("42")));
        assertTrue(SharePointDocumentBuilder.fieldValues(json("\"  \"")).isEmpty());
        assertTrue(SharePointDocumentBuilder.fieldValues(json("{\"TermGuid\":\"only\"}")).isEmpty());
    }

    @Test
    void idsAndTombstones() {
        assertEquals("sharepoint://contoso.sharepoint.com/drives/b!x/items/01AB", SharePointDocumentBuilder.documentId("contoso.sharepoint.com", "b!x", "01AB"));
        var t = SharePointDocumentBuilder.tombstone("id", "b!x", "01AB");
        assertEquals(org.opencrawling.core.document.DocumentAction.DELETE, t.action());
        assertEquals(List.of("DELETED"), t.metadata().get("sharepoint.status"));
    }

    @Test
    void urlHelpers() {
        assertEquals("drives/b/root/delta?$top=100", SharePointRepositoryConnector.stripExpand("drives/b/root/delta?$top=100&$expand=listItem%28%24expand%3Dfields%29"));
        assertEquals("drives/b/children?$top=5", SharePointRepositoryConnector.stripExpand("drives/b/children?$expand=x&$top=5"));
        assertEquals("/Shared%20Documents/R%26D%20%2B%20Ops", GraphClient.encodePath("Shared Documents/R&D + Ops"));
        assertEquals("https://graph.microsoft.us/.default", GraphClient.scopeFor(URI.create("https://graph.microsoft.us/v1.0/")));
        assertEquals("adele@contoso.com", SharePointPrincipals.fromLoginName("i:0#.f|membership|adele@contoso.com"));
        assertNull(SharePointPrincipals.fromLoginName("c:0(.s|true"));
    }
}
