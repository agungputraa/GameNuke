# GameNuke Remote Config — GitHub Actions Setup

Place the files in the repository as follows:

```text
RemoteNuke/
├── .github/
│   └── workflows/
│       └── validate-gamenuke-config.yml
├── gamenuke-remote-config.v1.json
├── gamenuke-remote-config.v1.json.sig
├── gamenuke-remote-config.schema.v1.json
├── remote-nuke-2026-01-public.pem
└── validate_gamenuke_remote_config.py
```

The workflow deliberately does not enable `setup-python`'s pip cache. The repository has only one
small CI dependency, so caching provides little benefit and would require a dependency manifest.
Without a manifest, `cache: pip` stops the job before validation with the “No file matched to
requirements.txt or pyproject.toml” error.

The fixed workflow uses `actions/checkout@v7` and `actions/setup-python@v7`, installs a bounded
`jsonschema` release, compiles the validator, then performs complete schema and semantic safety
validation. Do not add `ACTIONS_ALLOW_USE_UNSECURE_NODE_VERSION`; the selected actions are native
to the supported Node 24 runtime.

Revision 4 also verifies the detached Ed25519 signature. The workflow decodes the Base64 `.sig`,
requires an exact 64-byte Ed25519 signature, and verifies it with the public key. The private key
must never be uploaded to GitHub or added to the source ZIP.

After uploading, open **Actions → Validate GameNuke Remote Config → Run workflow**. The same job
also runs automatically when the config, schema, validator, or workflow changes on `main`, and on
matching pull requests.
