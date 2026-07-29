# External client changes

Read for a new/changed SMTP, S3, or outbound HTTP client and its integration test. Business orchestration is tested in the service functional spec; this guide proves one client against a real/protocol-compatible dependency, without the full app or HTTP entrypoint.

## Integration spec

File: `gateway/core/.../it/<Client>ClientSpec`. Extend `ZWordSpecBase`, needed feature arbitraries, and `DockerComposeBase`. One compose dependency per suite:

| Compose | Dependency |
|---|---|
| `email.yaml` | MailHog/SMTP |
| `s3.yaml` | s3mock/S3 |
| `wiremock.yaml` | Wiremock/outbound HTTP |

Override the compose file and matching test client's `ExposedServices`; `DockerComposeBase` starts it once. Prefer `withContext` with container-derived config for a client without per-test mocks. Use a fresh `TestContext` only when fresh mocks are required.

Assert the dependency's observable state, not merely effect success:

- MailHog inbox count/content;
- S3 object bytes in order;
- Wiremock request method, URL, body as applicable, and count;
- exact `ServiceError`, including message, for failures.

Reset dependency state between tests (`clearInbox`, `emptyAllBuckets`, `wiremockClient.reset`). Tests are order-independent and use fresh arbitrary values.

Run:

```sh
sbt "gateway-core/testOnly *<Client>ClientSpec"
```

If endpoint-visible behavior changes, also update the service functional and acceptance tests plus feature doc.
