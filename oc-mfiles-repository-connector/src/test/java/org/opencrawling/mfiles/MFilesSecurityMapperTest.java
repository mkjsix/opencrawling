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
package org.opencrawling.mfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.security.PermissionRule;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Fail-closed mapping of M-Files permissions to OIS security. */
class MFilesSecurityMapperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static MFilesSecurityMapper mapper(Map<String, String> extra) {
        Map<String, String> config = new HashMap<>(Map.of("vaultGuid", MockMFiles.VAULT));
        config.putAll(extra);
        Map<String, String> names = Map.of("user:12", "sarah.connor", "group:5", "legal-department");
        return new MFilesSecurityMapper(MFilesProperties.fromConfiguration(config),
                (id, group) -> Optional.ofNullable(names.get((group ? "group:" : "user:") + id)));
    }

    private static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    private static List<String> identities(MFilesSecurityMapper.Mapped mapped, String access) {
        return mapped.security().permissions().stream().filter(r -> r.access().equals(access)).map(PermissionRule::identity).toList();
    }

    @Test
    void componentsAreIntersectedAndDenyWins() throws Exception {
        MFilesSecurityMapper.Mapped mapped = mapper(Map.of()).fromPermissions(
                Optional.of(json(MockMFiles.fixture("permissions-48192.json"))));
        assertEquals(List.of("mfiles:user:12", "mfiles:group:5"), identities(mapped, "read"));
        assertEquals(List.of("mfiles:user:9"), identities(mapped, "deny"));
        assertEquals(List.of("mfiles:user:12"), mapped.users());
        assertEquals(List.of("mfiles:group:5"), mapped.groups());
        assertFalse(mapped.security().inheritanceEnabled());
    }

    @Test
    void denyInOneComponentOverridesAllowInAnother() throws Exception {
        MFilesSecurityMapper.Mapped mapped = mapper(Map.of()).fromPermissions(Optional.of(json("""
                {"A":[{"UserOrGroupID":12,"IsGroup":false,"ReadPermission":"Allow"}],
                 "B":[{"UserOrGroupID":12,"IsGroup":false,"ReadPermission":"Deny"}]}""")));
        assertTrue(identities(mapped, "read").isEmpty());
        assertEquals(List.of("mfiles:user:12"), identities(mapped, "deny"));
    }

    @Test
    void unreadablePermissionsGiveNobodyNotPublic() throws Exception {
        MFilesSecurityMapper m = mapper(Map.of());
        assertTrue(m.fromPermissions(Optional.empty()).security().permissions().isEmpty());
        // numeric permission codes are undocumented: never guessed
        assertTrue(m.fromPermissions(Optional.of(json("""
                [{"UserOrGroupID":12,"IsGroup":false,"ReadPermission":1}]"""))).security().permissions().isEmpty());
        // pseudo-users (metadata-driven) cannot be resolved
        assertTrue(m.fromPermissions(Optional.of(json("""
                [{"UserOrGroupID":3,"IsPseudoUser":true,"ReadPermission":"Allow"}]"""))).security().permissions().isEmpty());
    }

    @Test
    void accessFlagsReadBitIsHonoured() throws Exception {
        MFilesSecurityMapper.Mapped mapped = mapper(Map.of()).fromPermissions(Optional.of(json("""
                {"AccessControlList":{"UserOrGroupAccessList":[
                  {"UserOrGroupID":12,"IsGroup":false,"AccessFlags":3},
                  {"UserOrGroupID":5,"IsGroup":true,"AccessFlags":2}]}}""")));
        assertEquals(List.of("mfiles:user:12"), identities(mapped, "read"));
    }

    @Test
    void fallbackPrincipalsApplyWhenNothingIsReadable() {
        MFilesSecurityMapper.Mapped mapped = mapper(Map.of("fallbackPrincipals", "group:records-managers,user:auditor"))
                .fromPermissions(Optional.empty());
        assertEquals(List.of("records-managers", "auditor"), identities(mapped, "read"));
        assertEquals("auditor,records-managers", mapped.acl());
        assertEquals(List.of("auditor"), mapped.users());
    }

    @Test
    void publicGroupMapsToPublic() throws Exception {
        MFilesSecurityMapper.Mapped mapped = mapper(Map.of("publicGroupIds", "1"))
                .fromPermissions(Optional.of(json(MockMFiles.fixture("permissions-everyone.json"))));
        assertEquals("public", mapped.acl());
        assertEquals(List.of("public"), identities(mapped, "read"));
    }

    @Test
    void nameFormatResolvesKnownPrincipalsAndKeepsTokensForTheRest() throws Exception {
        MFilesSecurityMapper.Mapped mapped = mapper(Map.of("principalFormat", "name"))
                .fromPermissions(Optional.of(json("""
                        [{"UserOrGroupID":12,"IsGroup":false,"ReadPermission":"Allow"},
                         {"UserOrGroupID":5,"IsGroup":true,"ReadPermission":"Allow"},
                         {"UserOrGroupID":77,"IsGroup":true,"ReadPermission":"Allow"}]""")));
        assertEquals(List.of("sarah.connor", "legal-department", "mfiles:group:77"), identities(mapped, "read"));
    }

    @Test
    void qualifiedPrincipalsCarryTheVault() throws Exception {
        MFilesSecurityMapper.Mapped mapped = mapper(Map.of("qualifyPrincipals", "true"))
                .fromPermissions(Optional.of(json("""
                        [{"UserOrGroupID":12,"IsGroup":false,"ReadPermission":"Allow"}]""")));
        assertEquals(List.of(MockMFiles.VAULT_KEY + "/mfiles:user:12"), identities(mapped, "read"));
    }

    @Test
    void vafTokensKeepTheirKind() throws Exception {
        MFilesSecurityMapper.Mapped mapped = mapper(Map.of()).fromTokens(
                json("[\"mfiles:user:12\",\"mfiles:group:3\",\"mfiles:role:3\",\"DOMAIN\\\\Finance\"]"),
                json("[\"mfiles:user:9\"]"));
        assertEquals(List.of("mfiles:user:12", "mfiles:group:3", "mfiles:role:3", "DOMAIN\\Finance"), identities(mapped, "read"));
        assertEquals(List.of("mfiles:user:9"), identities(mapped, "deny"));
        assertEquals(List.of("mfiles:group:3", "mfiles:role:3", "DOMAIN\\Finance"), mapped.groups());
        assertTrue(mapper(Map.of()).fromTokens(json("[]"), null).security().permissions().isEmpty());
    }
}
