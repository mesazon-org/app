import { ConfigContext, ExpoConfig } from "expo/config";

const IS_DEV = process.env.APP_VARIANT === "development";
const IS_PREVIEW = process.env.APP_VARIANT === "preview";
const getUniqueIdentifier = () => {
  if (IS_DEV) {
    return "com.anonymous.templateforeak.dev";
  }

  if (IS_PREVIEW) {
    return "com.anonymous.templateforeak.preview";
  }

  return "com.anonymous.templateforeak";
}

const getAppName = () => {
  if (IS_DEV) {
    return "template-for-eak Development";
  }

  if (IS_PREVIEW) {
    return "template-for-eak Preview";
  }

  return "template-for-eak";
}

export default ({ config }: ConfigContext): ExpoConfig => ({
  ...config,
  name: getAppName(),
  slug: "template-for-eak",
  version: "1.0.0",
  orientation: "portrait",
  icon: "./assets/icon.png",
  userInterfaceStyle: "light",
  newArchEnabled: true,
  splash: {
    image: "./assets/splash-icon.png",
    resizeMode: "contain",
    backgroundColor: "#ffffff",
  },
  ios: {
    supportsTablet: true,
    bundleIdentifier: getUniqueIdentifier(),
  },
  android: {
    adaptiveIcon: {
      foregroundImage: "./assets/adaptive-icon.png",
      backgroundColor: "#ffffff",
    },
    edgeToEdgeEnabled: true,
    package: getUniqueIdentifier(),
  },
  web: {
    favicon: "./assets/favicon.png",
  },
  extra: {
    eas: {
      projectId: "74bbe689-4a7e-4c70-95d8-71583245635c",
    },
  },
  owner: "eak-cy",
});
