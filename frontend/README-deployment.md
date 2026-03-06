# Deploying with EAS (Expo Application Services)

This app uses EAS workflows for CI/CD. The free tier includes **15 builds per month** for Android and iOS.  
See [Expo billing plans](https://docs.expo.dev/billing/plans/) for details.

---

## Configuration

| File | Purpose |
|------|--------|
| `.eas/workflows/*.yml` | Define workflow jobs (e.g. build triggers) |
| `eas.json` | Define build profiles: `development`, `preview`, `production` |

---

## Run a workflow build

Start the production workflow (as defined in the YAML):

```bash
npx eas-cli@latest workflow:run create-production-builds.yml
```

Or use the npm script:

```bash
npm run deploy
```

---

## Run a one-off EAS build

Build with the EAS build service and choose platform and profile:

```bash
eas build --platform android --profile development
```

**Note:** iOS builds are currently configured with `simulator: true` until a paid Apple Developer account is available for real device builds.
