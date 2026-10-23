# Graph mock: a Microsoft Entra ID + Microsoft Graph (SharePoint) emulator

A real SharePoint Online tenant can't be started in a container, so this emulator stands in for one. It is a small,
stateful emulator of the **Entra ID client-credentials token endpoint** and the **Microsoft Graph v1.0 SharePoint APIs**
the connector uses. It serves a generated site, so the SharePoint repository connector can be tested end to end, over
real HTTP, without a Microsoft 365 tenant. It is one Python file using only the standard library.

> **What it is not.** The emulator follows Microsoft's Graph documentation (learn.microsoft.com): the token response,
> `grantedToV2` permissions, sharing links, the `deleted` facet, `@odata.nextLink` / `@odata.deltaLink`, 410 resync with
> a `Location`, `Retry-After`. Where the documentation is silent, for example exactly which permissions an app-only caller
> sees, it uses the shape the connector expects. It proves the connector against a Graph-shaped service; it does not
> prove Microsoft's exact behaviour. Run the connector's read-only live probe against a real tenant for that.

## Running it

```bash
python3 oc-sharepoint-repository-connector/graph-mock/graph_mock.py                    # http://localhost:8080

docker build -t opencrawling/graph-mock:local oc-sharepoint-repository-connector/graph-mock
docker run --rm -p 8091:8080 opencrawling/graph-mock:local                               # http://localhost:8091
```

Point the connector at it:

```yaml
tenant-id: contoso-tenant
client-id: opencrawling-test-app
client-secret: test-secret
authority-url: http://localhost:8091
graph-url: http://localhost:8091/v1.0
site-url: https://contoso.sharepoint.com/sites/engineering
```

| Environment variable | Default | Meaning |
|---|---|---|
| `GRAPH_PORT` | `8080` | Listening port |
| `GRAPH_TENANT`, `GRAPH_CLIENT_ID`, `GRAPH_CLIENT_SECRET` | `contoso-tenant`, `opencrawling-test-app`, `test-secret` | The accepted tenant and app registration |
| `GRAPH_SHAREPOINT_HOST`, `GRAPH_SITE_PATH` | `contoso.sharepoint.com`, `/sites/engineering` | The site |
| `GRAPH_FILE_COUNT` | `40` | Generated files |
| `GRAPH_SEED` | `42` | The site is generated deterministically from this seed |
| `GRAPH_PAGE_SIZE` | `200` | Default page size (the client's `$top` wins, up to 999) |
| `GRAPH_TOKEN_SECONDS` | `3599` | Access token lifetime |
| `GRAPH_PUBLIC_URL` | (request host) | Base URL used in links, when clients reach the emulator through another name |
| `GRAPH_QUIET` | `false` | Suppress the request log |

## The generated site

- **Libraries:** two document libraries, `Documents` and `Contracts`, with folders (`Specs`, `Specs/2026`, `Policies`,
  `Archive`). There is also a `business` (OneDrive) drive, which the connector must ignore.
- **Files:** real **PDF, DOCX and TXT** files that Apache Tika extracts. Every file's text carries a unique reference code
  `SPX-<itemId>-e<version>` that changes with each edit.
- **List item columns:** `Title`, `ProjectCode`, `DocumentStatus` (choice), `Classification` (managed metadata term),
  `Reviewers` (people), `Budget` (number), plus system columns that the connector must skip.
- **Permissions:** six patterns, repeating by file number:
  0. "Everyone except external users";
  1. an Entra group with write and one user with read;
  2. the "Engineering Owners" SharePoint group plus a specific-people sharing link;
  3. only an organization and an anonymous sharing link;
  4. one user;
  5. no permissions at all.
- **Delta:** the first enumeration lists every item in an order that puts some files before their folders. Later calls
  with a token return only the changes, the last occurrence of each item only. A deleted folder is reported on its own,
  without its children.
- **Download URLs:** pre-authenticated and valid for an hour. They **reject any request that carries an `Authorization`
  header**, so a client that forwards its Graph token there fails loudly. `/content` answers with a 302 to a fresh
  download URL.

## Mock-only administration (`/mock/…`, not authenticated)

| Endpoint | Effect |
|---|---|
| `GET /mock/stats` | Live files (total and per library), deleted items, tokens issued, requests per route |
| `GET /mock/files` | Every live file with its library and permission pattern |
| `GET /mock/items/{id}` | An item's name, path, eTag, text and permissions (ground truth for tests) |
| `POST /mock/files` | Add a file: `{"drive", "parent", "name", "text", "title", "permissions"}` |
| `POST /mock/items/{id}/modify` | New version: `{"appendText", "name"}`; reported by delta |
| `POST /mock/items/{id}/delete` | Delete a file or a folder (with its contents); reported by delta |
| `POST /mock/items/{id}/permissions` | Replace the permissions: `{"permissions": [...]}`. **Not** reported by delta, as on Graph without `Sites.FullControl.All` |
| `POST /mock/drives/{driveId}/resync` | Make every earlier delta token answer 410 Gone with a restart `Location` |
| `POST /mock/faults` | Fail the next `count` requests whose URL starts with `path` with `status` (and `retryAfter` seconds) |
| `POST /mock/expire-tokens`, `POST /mock/expire-downloads` | Invalidate every access token / download URL |
| `POST /mock/reset` | Regenerate the site from the seed |

## Used by

- `SharePointMockServerTest` runs the connector scenarios over HTTP:
  - a full crawl reconciled with `/mock/stats`;
  - delta increments;
  - a deleted folder;
  - the permission-only change that only a tree crawl notices;
  - a resync;
  - throttling and expired download URLs;
  - a wrong secret.

  `scripts/test-sharepoint-repository-connector.sh` starts the emulator and runs these tests.
- `scripts/test-sharepoint-decoupled.sh` with `docker/docker-compose-decoupled-with-sharepoint.yml` runs the whole
  pipeline from the emulator through Kafka, Tika, Ollama and pgvector to MCP secure search.
