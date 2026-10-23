# OpenCrawling - SharePoint Repository Connector

The SharePoint Repository Connector (`oc-sharepoint-repository-connector`) crawls the document libraries of a
**SharePoint Online** site through **Microsoft Graph v1.0**. It streams the files into the OpenCrawling pipeline as Open
Ingestion Standard (OIS) documents, with their list item columns, content and permissions (issue #126). It authenticates
app-only with the OAuth 2.0 client-credentials grant against Microsoft Entra ID.

## Prerequisites

1. **An app registration in Microsoft Entra ID** (Entra admin center → App registrations → New registration), with:
   - a **client secret** (Certificates & secrets). Note the secret's *value*, the **Application (client) ID** and the
     **Directory (tenant) ID**;
   - the Microsoft Graph **application** permission **`Sites.Read.All`**, or **`Sites.Selected`** plus a `read` grant on
     the site to crawl (`POST /sites/{site-id}/permissions`, made by an administrator);
   - **admin consent** for that permission. Without it the token is issued but every Graph call answers 403.

   The connector only **reads**: it never creates, changes or deletes anything in SharePoint.
2. **The site** to crawl, as its URL (`https://contoso.sharepoint.com/sites/engineering`) or its Graph site id
   (`host,siteCollectionId,webId`), and optionally the names of the document libraries to crawl (all of the site's
   document libraries by default).
3. **Network access** from the OpenCrawling runtime to `login.microsoftonline.com` and `graph.microsoft.com` (or the
   sovereign-cloud equivalents, see `authority-url` and `graph-url`), and to the `*.sharepoint.com` download URLs.
4. **OpenCrawling side.** A running OpenCrawling runtime (Java 25). The client secret is a secret: supply it through the
   Admin UI, environment variables or your secret store, never through source control.

Use the Admin UI's **Test Connection** button, or `oc connector check --name <name> --type repository`, before the first
crawl. Both get a token, read the site and list its document libraries, and report a configured library that doesn't
exist.

## Features

1. **Delta crawl** (`crawl-mode: delta`, the default): `GET /drives/{id}/root/delta`. The first run enumerates the whole
   library; later runs fetch only what changed since the stored delta link. Folders and deletes are applied page by page,
   a deleted folder takes its files with it, and an expired delta link (410 Gone) triggers a full re-enumeration that
   reconciles with the stored state.
2. **Tree crawl** (`crawl-mode: tree`): a traversal of the folders from `root-folder-path`, optionally recursive. With
   `incremental: true` it compares each file's eTag and permission fingerprint with the previous run, re-emits only what
   changed and sends tombstones for what vanished. It is the mode that notices **permission-only changes** (see below).
3. **Libraries:** every document library of the site, or those named in `drive-names` (or one `drive-id`). OneDrive
   (`business`) drives and non-library lists are ignored.
4. **List item columns** (`includeCustomFields`): each column of the file's list item, as `sharepoint_field_<InternalName>`,
   with person, lookup and managed-metadata values reduced to their labels. SharePoint's system columns are skipped.
5. **Content:** the file is downloaded through its pre-authenticated `@microsoft.graph.downloadUrl` **without** the Graph
   token, falling back to `/content` when that URL has expired. It is streamed to a temporary file, handed to the
   claim-check store and deleted when the stream is closed. Files above `max-file-size-bytes` are indexed by metadata only.
6. **Security:** each file's Graph permissions mapped to OIS permissions, failing closed (see *Security mapping*).
7. **Throttling:** 429 and 503 honour `Retry-After` through one gate shared by all workers, with exponential back-off and
   jitter when the header is missing; a throttled or failing item is never mistaken for a deleted one. An expired token
   (401) is renewed once.
8. **Token safety:** redirects are followed by hand, and the token is only ever sent to the configured Graph host.
9. **Bounded parallelism** (Java 25 `StructuredTaskScope` + `ObservabilityTask`) and an optional `max-documents` cap for
   test runs. Requests carry the `ISV|OpenCrawling|OpenCrawling-SharePoint-Connector/1.0` User-Agent Microsoft recommends
   for throttling diagnostics.

## Configuration Properties

The connector is configured either through Spring properties (`spring.opencrawling.connector.type=sharepoint` plus the
keys below under `spring.opencrawling.connector.sharepoint.`; YAML lists are accepted for list keys) or through a connector
configuration in the Admin UI / `connectors.json` (class `org.opencrawling.sharepoint.SharePointRepositoryConnector`,
camelCase keys).

| Spring property (`spring.opencrawling.connector.sharepoint.*`) | `connectors.json` key | Default | Description |
|---|---|---|---|
| `tenant-id` | `tenantId` | — | Required. Directory (tenant) ID or domain |
| `client-id` | `clientId` | — | Required. Application (client) ID |
| `client-secret` | `clientSecret` | — | Required. Client secret value |
| `authority-url` | `authorityUrl` | `https://login.microsoftonline.com` | Entra ID authority (sovereign clouds use their own) |
| `graph-url` | `graphUrl` | `https://graph.microsoft.com/v1.0` | Graph base URL including the version |
| `site-url` | `siteUrl` | — | The site, e.g. `https://contoso.sharepoint.com/sites/engineering`. This or `site-id` is required |
| `site-id` | `siteId` | — | Graph site id (`host,siteCollectionId,webId`) instead of the URL |
| `drive-names` (or `drive-name`) | `driveNames` (or `driveName`) | all | Document libraries to crawl, by display name |
| `drive-id` | `driveId` | — | One document library by id instead of names |
| `crawl-mode` | `crawlMode` | `delta` | `delta` or `tree` |
| `root-folder-path` | `rootFolderPath` | `/` | Tree mode: folder to start from, relative to the library root |
| `include-subfolders` | `includeSubfolders` | `true` | Tree mode: descend into sub-folders |
| `include-content-stream` | `includeContentStream` | `true` | Download file content |
| `max-file-size-bytes` | `maxFileSizeBytes` | `52428800` | Larger files are indexed by metadata only |
| `include-custom-fields` | `includeCustomFields` | `true` | Emit list item columns |
| `field-prefix` | `fieldPrefix` | `sharepoint_field_` | Prefix of list item column keys |
| `include-acls` | `includeAcls` | `true` | Read item permissions. When `false`, every document is **public** |
| `user-identity` | `userIdentity` | `email` | Users as lower-case e-mail / UPN (`email`) or Entra object id (`id`) |
| `fallback-principals` | `fallbackPrincipals` | — | Readers for files with no readable permission, comma-separated: `group:X`, `user:Y`, a bare name (a group) or `public`. Empty means nobody |
| `everyone-identity` | `everyoneIdentity` | — | Identity granted for SharePoint's "Everyone" claim (e.g. `public`); empty grants nothing |
| `everyone-except-external-identity` | `everyoneExceptExternalIdentity` | — | Identity for "Everyone except external users" (e.g. an all-employees group); empty grants nothing |
| `organization-link-identity` | `organizationLinkIdentity` | — | Identity for organization-wide sharing links; empty grants nothing |
| `anonymous-link-identity` | `anonymousLinkIdentity` | — | Identity for "anyone with the link" sharing links; empty grants nothing |
| `incremental` | `incremental` | `true` | Keep a crawl state per library (delta link, eTags, permission fingerprints) |
| `state-directory` | `stateDirectory` | `data/sharepoint-state` | Where the crawl state is kept (one JSON file per library) |
| `batch-size` | `batchSize` | `100` | Page size (`$top`, 1–999) of children and delta requests |
| `parallelism` | `parallelism` | `4` | Items processed at once |
| `timeout-seconds` | `timeoutSeconds` | `30` | HTTP timeout |
| `max-retries` | `maxRetries` | `5` | Retries on 429, 503, 504 and connection failures |
| `initial-backoff-ms` | `initialBackoffMs` | `1000` | First back-off when a throttled response has no `Retry-After` |
| `max-documents` | `maxDocuments` | `0` | Stop after N files (0 = no limit; for test runs) |

A job path other than empty, `default` or `/` replaces `root-folder-path`, and that run is a non-incremental tree crawl.

## Example `application.yml`

```yaml
spring:
  opencrawling:
    repository-connector-type: sharepoint
    connector:
      type: sharepoint
      sharepoint:
        tenant-id: "00000000-0000-0000-0000-000000000000"
        client-id: "11111111-1111-1111-1111-111111111111"
        client-secret: "${SHAREPOINT_CLIENT_SECRET}"
        site-url: "https://contoso.sharepoint.com/sites/engineering"
        drive-names:
          - "Documents"
          - "Contracts"
        crawl-mode: "delta"
        everyone-except-external-identity: "all-employees"
        incremental: true
        parallelism: 4
```

## OIS Document Mapping

A crawled file (`action: UPSERT`):

```json
{
  "id": "sharepoint://contoso.sharepoint.com/drives/b!AbC.../items/01ABCDEF2345GHIJ",
  "action": "UPSERT",
  "metadata": {
    "name": ["Design Spec.docx"],
    "title": ["Design Specification"],
    "mimeType": ["application/vnd.openxmlformats-officedocument.wordprocessingml.document"],
    "sharepoint.siteId": ["contoso.sharepoint.com,7c0e...,9a3f..."],
    "sharepoint.siteUrl": ["https://contoso.sharepoint.com/sites/engineering"],
    "sharepoint.driveId": ["b!AbC..."],
    "sharepoint.driveName": ["Documents"],
    "sharepoint.itemId": ["01ABCDEF2345GHIJ"],
    "sharepoint.listItemUniqueId": ["5f1c..."],
    "sharepoint.name": ["Design Spec.docx"],
    "sharepoint.path": ["/Specs/2026/Design Spec.docx"],
    "sharepoint.webUrl": ["https://contoso.sharepoint.com/sites/engineering/Shared%20Documents/Specs/2026/Design%20Spec.docx"],
    "sharepoint.size": ["48213"],
    "sharepoint.etag": ["\"{01ABCDEF-2345-GHIJ},3\""],
    "sharepoint.cTag": ["\"c:{01ABCDEF-2345-GHIJ},3\""],
    "sharepoint.createdDateTime": ["2026-03-02T09:14:00Z"],
    "sharepoint.createdBy": ["adele.vance@contoso.com"],
    "sharepoint.lastModifiedDateTime": ["2026-10-20T16:02:11Z"],
    "sharepoint.lastModifiedBy": ["megan.bowen@contoso.com"],
    "sharepoint_field_ProjectCode": ["ENG-042"],
    "sharepoint_field_DocumentStatus": ["Approved"],
    "sharepoint_field_Classification": ["Internal"],
    "sharepoint_field_Reviewers": ["Adele Vance", "Megan Bowen"]
  },
  "security": {
    "inheritanceEnabled": false,
    "permissions": [
      { "identity": "0d1e6b2a-0000-4000-8000-00000000e001", "identityType": "group", "access": "write" },
      { "identity": "adele.vance@contoso.com", "identityType": "user", "access": "read" }
    ]
  }
}
```

A deleted file (`action: DELETE`) carries the same `id` and `sharepoint.status: ["DELETED"]`.

### Security mapping

| Graph permission | OIS rule |
|---|---|
| `grantedToV2` / `grantedToIdentitiesV2` user, role `read` | `read` for the user (e-mail / UPN, or object id with `user-identity: id`) |
| … role `write` or `owner` | `write` (which also allows reading) |
| Entra security or Microsoft 365 group | the group's object id |
| SharePoint group (`siteGroup`) | `sharepoint-group:<siteId>:<groupId>` |
| "Everyone" claim (`c:0(.s\|true`) | `everyone-identity`, or nothing |
| "Everyone except external users" (`spo-grid-all-users`) | `everyone-except-external-identity`, or nothing |
| Group claims (`c:0t.c\|tenant\|…`, `c:0o.c\|federateddirectoryclaimprovider\|…`) | the group's object id |
| Sharing link, scope `users` | the people the link was shared with |
| Sharing link, scope `organization` / `anonymous` | `organization-link-identity` / `anonymous-link-identity`, or nothing |
| Sharing link, scope `existingAccess`, and any other role | nothing |

A file whose permissions grant nobody gets `fallback-principals`, or no reader at all. Since the MCP server treats an empty
permission list as "nobody", such a file is **never** made public. The broad SharePoint audiences (Everyone, organization
and anonymous links) only grant access when you map them to an identity your users carry, for example `public` or an
all-employees group. To let MCP clients match these rules, pass users' e-mail (or object id) as `userPrincipal` and their
Entra group object ids (and any `sharepoint-group:` tokens) as `userRoles`.

## Incremental Crawls and Delete Detection

With `incremental: true` the connector keeps one state file per library under `state-directory`: the delta link, and per
item its parent, name, eTag and permission fingerprint (a SHA-256 of the mapped rules).

- **Delta mode:** each run asks Graph for what changed since the stored delta link. New and edited files are emitted,
  files and folders reported `deleted` become tombstones (a folder's tombstone covers every file under it), moves update
  the path. The delta link only advances after a run without failures, so a failed item is retried. A 410 Gone resync
  re-enumerates the library, skips files whose eTag and permissions didn't change and tombstones what vanished.
- **Tree mode:** a full traversal compared with the state: changed eTags or permission fingerprints are re-emitted,
  vanished files get tombstones. A traversal cut short (`max-documents`) infers no deletes.
- **One state per library:** delta crawls and tree crawls of the whole library (`root-folder-path: /` with sub-folders)
  share the same state file, so either mode skips what the other already emitted. A tree crawl of a narrower scope (a
  sub-folder, or without sub-folders) keeps a state of its own, since every file outside its scope would otherwise look
  deleted.
- **Permission-only changes** are reported by Graph delta only to apps with `Sites.FullControl.All`. With the read-only
  permissions this connector asks for, schedule a **tree** crawl now and then (for example nightly, with delta crawls in
  between) over the whole library. It reuses the delta crawls' state and re-emits only the files whose permissions
  changed.

## Admin UI & CLI Integration

### Admin UI (`oc-admin-ui`)

Select **Microsoft SharePoint (Microsoft Graph API)** as the repository connector. The form has fields for every property
above. The broad SharePoint audiences are grouped under **Broad Access Grants**. **Test Connection** gets a token, reads the
site and lists its libraries.

### CLI (`oc-cli`)

```bash
# Check the SharePoint connection
oc connector check --name sharepoint-engineering --type repository
```

## Testing & Execution

### 1. Unit and mock-server tests

```bash
mvn test -pl oc-sharepoint-repository-connector -am
```

The unit tests run against MockWebServer with Graph response fixtures in `src/test/resources/sharepoint-mock-responses/`.
They cover tokens and renewal, throttling, redirects, paging, delta, the tree crawl, tombstones, downloads, list item
columns and the security mapping.

### 2. Standalone connector test

```bash
./scripts/test-sharepoint-repository-connector.sh
```

The script starts the [Graph mock](graph-mock/README.md), an Entra ID + Microsoft Graph emulator, on a local port
(`GRAPH_MOCK_PORT`, default `18180`) and runs the tests above with `GRAPH_MOCK_URL` set, which adds
`SharePointMockServerTest`: seven connector scenarios over real HTTP (full crawl, delta increments, a deleted folder, a
permission-only change, a resync, throttling and expired download URLs, a wrong secret).

### 3. Decoupled pipeline test

```bash
./scripts/test-sharepoint-decoupled.sh                     # images from docker/Dockerfile.*
LOCAL_BUILD=true ./scripts/test-sharepoint-decoupled.sh    # faster local builds from docker/local/
```

The script uses `docker/docker-compose-decoupled-with-sharepoint.yml`, with the Graph mock in a container. It crawls the
mock site through Kafka, Tika, Ollama and pgvector, then checks:
- every live file is indexed once, with its path, list item columns and permission rules;
- MCP `secureVectorSearch`, `getDocumentContent` and `listAccessibleSources` give each user exactly their files and keep
  files without readers closed;
- a delta increment carries an edit, a file delete, a folder delete and a new file;
- a permission-only change is not seen by delta, and is reconciled by a tree crawl.

### 4. Read-only live probe against a tenant

`SharePointLiveProbeTest` runs only when `SHAREPOINT_LIVE_PROBE=1` is set. It crawls up to `SHAREPOINT_MAX_DOCUMENTS`
files (default 25) of a real site with GET requests only, reads and discards their content, keeps the crawl state in a
temporary directory, and prints each file's path and mapped permissions with a count per identity. Compare those with
SharePoint's own **Manage access** view to see what your app registration is allowed to read.

```bash
SHAREPOINT_LIVE_PROBE=1 SHAREPOINT_TENANT_ID=… SHAREPOINT_CLIENT_ID=… SHAREPOINT_CLIENT_SECRET=… \
SHAREPOINT_SITE_URL=https://<tenant>.sharepoint.com/sites/<site> SHAREPOINT_DRIVE_NAMES=Documents \
mvn test -pl oc-sharepoint-repository-connector -am -Dtest=SharePointLiveProbeTest -Dsurefire.failIfNoSpecifiedTests=false
```

## Microsoft Graph Endpoints

| Purpose | Graph v1.0 |
|---|---|
| Token | `POST {authority}/{tenant}/oauth2/v2.0/token` (`client_credentials`, scope `https://graph.microsoft.com/.default`) |
| Site | `GET /sites/{host}:/{path}`, `GET /sites/{site-id}` |
| Libraries | `GET /sites/{site-id}/drives` (`driveType` `documentLibrary`), `GET /drives/{drive-id}` |
| Delta | `GET /drives/{drive-id}/root/delta` (`@odata.nextLink`, `@odata.deltaLink`; 410 with `Location` on resync) |
| Tree | `GET /drives/{drive-id}/items/{item-id}/children`, `GET /drives/{drive-id}/root:/{path}` |
| Item, list item columns | `GET /drives/{drive-id}/items/{item-id}`, `…?$expand=listItem($expand=fields)` |
| Permissions | `GET /drives/{drive-id}/items/{item-id}/permissions` |
| Content | `@microsoft.graph.downloadUrl` (no token), else `GET /drives/{drive-id}/items/{item-id}/content` (302) |

## Known Limitations

- **Not yet run against a real tenant.** The connector is covered by the unit tests, the Graph mock scenarios and the
  decoupled pipeline test. The mock follows Microsoft's documentation, but it doesn't prove Microsoft's exact behaviour. Run
  the read-only live probe first.
- **App-only permission visibility.** Microsoft documents that `/permissions` may return only part of the permissions to
  callers that aren't owners. What an app with `Sites.Read.All` sees has not been verified on a real tenant.
- **SharePoint group membership is not expanded.** Graph has no API for SharePoint groups' members, so they're emitted as
  `sharepoint-group:<siteId>:<groupId>` tokens. Your MCP clients must pass those tokens in `userRoles` for users who
  belong to them. Entra groups are emitted by object id and are not expanded either.
- **Permission-only changes** need a tree crawl (see above), unless the app has `Sites.FullControl.All`.
- **Renamed folders in delta mode.** Graph delta doesn't report the new path of files under a renamed folder. Their
  `sharepoint.path` stays stale until the files change or a tree crawl runs.
- **Document libraries only.** Generic lists, list attachments, pages and OneDrive drives are out of scope, and only the
  current version of each file is indexed.
