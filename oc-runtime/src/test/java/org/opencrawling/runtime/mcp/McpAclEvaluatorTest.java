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
package org.opencrawling.runtime.mcp;

import org.junit.jupiter.api.Test;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpAclEvaluatorTest {

    @Test
    void testParseRoles() {
        assertEquals(List.of(), McpAclEvaluator.parseRoles(null));
        assertEquals(List.of(), McpAclEvaluator.parseRoles(""));
        assertEquals(List.of(), McpAclEvaluator.parseRoles("   "));
        assertEquals(List.of("finance", "engineering"), McpAclEvaluator.parseRoles("finance, engineering"));
        assertEquals(List.of("admin", "hr"), McpAclEvaluator.parseRoles("admin,  , hr, "));
    }

    @Test
    void testPublicAccessDefault() {
        // Null metadata defaults to public
        assertTrue(McpAclEvaluator.isAccessible((Map<String, Object>) null, "alice", List.of("guest")));
        // Empty metadata defaults to public
        assertTrue(McpAclEvaluator.isAccessible(Map.of(), "bob", List.of()));
        // Explicit "public" acl
        assertTrue(McpAclEvaluator.isAccessible(Map.of("acl", "public"), "charlie", List.of()));
        assertTrue(McpAclEvaluator.isAccessible(Map.of("acl", "PUBLIC"), null, null));
    }

    @Test
    void testLegacyAclPrincipalAndRoles() {
        Map<String, Object> metadata = Map.of("acl", "john.doe");

        assertTrue(McpAclEvaluator.isAccessible(metadata, "john.doe", List.of()));
        assertTrue(McpAclEvaluator.isAccessible(metadata, "JOHN.DOE", List.of()));
        assertFalse(McpAclEvaluator.isAccessible(metadata, "jane.doe", List.of()));

        Map<String, Object> roleMeta = Map.of("acl", "engineering");
        assertTrue(McpAclEvaluator.isAccessible(roleMeta, "jane.doe", List.of("ENGINEERING", "devops")));
        assertFalse(McpAclEvaluator.isAccessible(roleMeta, "jane.doe", List.of("finance")));
    }

    @Test
    void testStructuredSecurityConfigAllowAndDeny() {
        // Alice has read access, but Finance is explicitly denied.
        SecurityConfig sc = new SecurityConfig(false, List.of(
                new PermissionRule("alice", "user", "Alice", "read"),
                new PermissionRule("finance", "group", "Finance", "deny")
        ));
        Map<String, Object> meta = Map.of("security", sc);

        // Alice without finance role: allowed
        assertTrue(McpAclEvaluator.isAccessible(meta, "alice", List.of("dev")));

        // Alice WITH finance role: denied! Explicit deny overrides read
        assertFalse(McpAclEvaluator.isAccessible(meta, "alice", List.of("FINANCE")));

        // Bob without matching permissions: denied
        assertFalse(McpAclEvaluator.isAccessible(meta, "bob", List.of("dev")));
    }

    @Test
    void testStructuredSecurityMapAllowAndDeny() {
        // Security object as Map (as deserialized from JSON / Kafka)
        Map<String, Object> perm1 = Map.of("identity", "marketing", "identityType", "group", "access", "read");
        Map<String, Object> perm2 = Map.of("identity", "intern", "identityType", "group", "access", "deny");
        Map<String, Object> securityMap = Map.of("permissions", List.of(perm1, perm2));
        Map<String, Object> meta = Map.of("security", securityMap);

        // User with marketing group -> allowed
        assertTrue(McpAclEvaluator.isAccessibleWithRolesCsv(meta, "dave", "marketing, hr"));

        // User with marketing AND intern group -> DENIED
        assertFalse(McpAclEvaluator.isAccessibleWithRolesCsv(meta, "dave", "marketing, intern"));

        // User with neither -> denied
        assertFalse(McpAclEvaluator.isAccessibleWithRolesCsv(meta, "dave", "sales"));
    }

    @Test
    void testFlattenedAllowedAndDeniedRead() {
        Map<String, Object> meta = Map.of(
                "security_allowed_read", List.of("public", "engineering"),
                "security_denied_read", List.of("contractor"),
                "acl", "engineering"
        );

        // Regular engineer -> allowed
        assertTrue(McpAclEvaluator.isAccessible(meta, "eve", List.of("engineering")));

        // Contractor engineer -> denied by explicit deny
        assertFalse(McpAclEvaluator.isAccessible(meta, "contractor-1", List.of("contractor", "engineering")));

        // Public user without contractor role -> allowed via public in allowed_read
        assertTrue(McpAclEvaluator.isAccessible(meta, "guest", List.of()));
    }

    @Test
    void testLuxirSuffixFields() {
        Map<String, Object> meta = Map.of(
                "security_allowed_read_ss", List.of("legal"),
                "security_denied_read_ss", List.of("temp"),
                "acl_ss", List.of("legal")
        );

        assertTrue(McpAclEvaluator.isAccessible(meta, "lawyer", List.of("LEGAL")));
        assertFalse(McpAclEvaluator.isAccessible(meta, "lawyer", List.of("LEGAL", "TEMP")));
        assertFalse(McpAclEvaluator.isAccessible(meta, "other", List.of("sales")));
    }

    @Test
    void testExplicitDenyOverridesPublic() {
        SecurityConfig sc = new SecurityConfig(false, List.of(
                new PermissionRule("public", "public", "Public", "read"),
                new PermissionRule("banned-user", "user", "Banned", "deny")
        ));
        Map<String, Object> meta = Map.of("security", sc);

        // General public -> allowed
        assertTrue(McpAclEvaluator.isAccessible(meta, "regular-user", List.of()));

        // Banned user -> DENIED
        assertFalse(McpAclEvaluator.isAccessible(meta, "banned-user", List.of()));
    }

    @Test
    void testCommaSeparatedFlatAclMatchesEveryReader() {
        Map<String, Object> mfilesMeta = Map.of("acl", "mfiles:user:12,mfiles:group:5");
        assertTrue(McpAclEvaluator.isAccessible(mfilesMeta, "mfiles:user:12", List.of()));
        assertTrue(McpAclEvaluator.isAccessible(mfilesMeta, "someone", List.of("mfiles:group:5")));
        assertFalse(McpAclEvaluator.isAccessible(mfilesMeta, "someone", List.of("mfiles:group:99")));

        assertTrue(McpAclEvaluator.isAccessible("engineering, finance", List.of(), List.of(), "bob", List.of("finance")));
    }
}
