# Template for EAK - React Expo App with i18n

A React Native Expo application with internationalization (i18n) support, featuring multi-language support, language persistence, and a beautiful language switcher interface.

## 🌟 Features

- ✅ **Multi-language Support** (English, Spanish, French)
- ✅ **Language Detection & Persistence** using AsyncStorage
- ✅ **Beautiful Language Switcher** with active state indicators
- ✅ **Type-safe Translations** with TypeScript
- ✅ **Custom i18n Hooks** for easy integration
- ✅ **React Native Compatible** i18n configuration
- ✅ **Expo SDK 53** with latest React Native features

## 📋 Prerequisites

Before running this project, ensure you have the following installed:

### 1. **Node.js & npm**
```bash
# Install Node.js (v18 or higher recommended)
# Download from: https://nodejs.org/
node --version  # Should be v18+
npm --version   # Should be v9+
```

### 2. **Expo CLI**
```bash
npm install -g @expo/cli
expo --version
```

### 3. **Git**
```bash
# Install Git
git --version
```

### 4. **For iOS Development (macOS only)**
```bash
# Install Xcode from App Store
# Install Xcode Command Line Tools
xcode-select --install

# Install CocoaPods
sudo gem install cocoapods
# OR using Homebrew
brew install cocoapods

# Install iOS Simulator (comes with Xcode)
```

### 5. **For Android Development**
```bash
# Install Android Studio
# Download from: https://developer.android.com/studio

# Install Android SDK (via Android Studio)
# - Android SDK Platform 35
# - Android SDK Build-Tools 35.0.0
# - Android NDK 27.1.12297006
# - CMake 3.22.1

# Set ANDROID_HOME environment variable
export ANDROID_HOME=$HOME/Library/Android/sdk
export PATH=$PATH:$ANDROID_HOME/emulator
export PATH=$PATH:$ANDROID_HOME/platform-tools

# Add to your shell profile (~/.zshrc, ~/.bash_profile, etc.)
echo 'export ANDROID_HOME=$HOME/Library/Android/sdk' >> ~/.zshrc
echo 'export PATH=$PATH:$ANDROID_HOME/emulator' >> ~/.zshrc
echo 'export PATH=$PATH:$ANDROID_HOME/platform-tools' >> ~/.zshrc
```

### 6. **Development Tools (Optional but Recommended)**
```bash
# Install VS Code or your preferred editor
# Install React Native Debugger (optional)
# Install Flipper (optional)
```

## 🚀 Getting Started

### 1. **Clone the Repository**
```bash
git clone <your-repo-url>
cd template-for-eak
```

### 2. **Install Dependencies**
```bash
npm install
```

### 3. **Environment Setup**

#### **For iOS (macOS only):**
```bash
# Generate iOS project (if not already done)
npx expo prebuild --platform ios

# Install CocoaPods dependencies
cd ios && pod install && cd ..
```

#### **For Android:**
```bash
# Generate Android project (if not already done)
npx expo prebuild --platform android

# The local.properties file should be created automatically
# If not, create android/local.properties with:
# sdk.dir=/Users/YOUR_USERNAME/Library/Android/sdk
```

### 4. **Run the Application**

#### **Using Expo CLI (Recommended):**
```bash
# Start the development server
npx expo start

# Run on iOS Simulator
npx expo run:ios

# Run on Android Emulator/Device
npx expo run:android

# Run on Web
npx expo run:web
```

#### **Using Expo Go (for testing without custom native code):**
```bash
# Start the development server
npx expo start

# Scan QR code with Expo Go app on your device
# Or press 'i' for iOS Simulator, 'a' for Android
```

## 📱 Running on Physical Devices

### **iOS Device:**
1. Connect your iPhone via USB
2. Trust the computer on your iPhone
3. Run: `npx expo run:ios --device`

### **Android Device:**
1. Enable Developer Options and USB Debugging
2. Connect your device via USB
3. Run: `npx expo run:android --device`

## 🌍 Internationalization (i18n)

### **Available Languages:**
- 🇺🇸 **English** (en)
- 🇪🇸 **Spanish** (es)
- 🇫🇷 **French** (fr)

### **Using Translations:**
```tsx
import { useTranslation } from 'react-i18next';

function MyComponent() {
  const { t } = useTranslation();
  
  return <Text>{t('welcome')}</Text>;
}
```

### **Language Switcher:**
The app includes a built-in language switcher component that allows users to change languages dynamically.

### **Adding New Languages:**
1. Create a new translation file in `i18n/locales/` (e.g., `de.json`)
2. Add the language to the resources in `i18n/index.ts`
3. Update the `LanguageSwitcher` component

## 🏗️ Project Structure

```
template-for-eak/
├── android/                 # Android native project
├── ios/                    # iOS native project
├── i18n/                   # Internationalization
│   ├── index.ts           # i18n configuration
│   └── locales/           # Translation files
│       ├── en.json        # English
│       ├── es.json        # Spanish
│       └── fr.json        # French
├── components/            # React components
│   └── LanguageSwitcher.tsx
├── hooks/                # Custom hooks
│   └── useI18n.ts
├── types/                # TypeScript declarations
│   └── json.d.ts
├── App.tsx               # Main app component
├── app.json              # Expo configuration
└── package.json          # Dependencies
```

## 🔧 Development

### **Available Scripts:**
```bash
npm start          # Start Expo development server
npm run android    # Run on Android
npm run ios        # Run on iOS
npm run web        # Run on Web
```

### **Building for Production:**
```bash
# Build for iOS
npx expo build:ios

# Build for Android
npx expo build:android

# Or use EAS Build (recommended)
npx eas build --platform ios
npx eas build --platform android
```

## 🐛 Troubleshooting

### **Common Issues:**

#### **iOS Build Issues:**
```bash
# Clean and reinstall pods
cd ios && pod deintegrate && pod install && cd ..

# Clean Xcode build
cd ios && xcodebuild clean && cd ..
```

#### **Android Build Issues:**
```bash
# Clean Gradle build
cd android && ./gradlew clean && cd ..

# Check Android SDK installation
echo $ANDROID_HOME
ls $ANDROID_HOME
```

#### **Metro Bundler Issues:**
```bash
# Clear Metro cache
npx expo start --clear

# Reset cache
npx expo start --reset-cache
```

#### **Dependencies Issues:**
```bash
# Clear npm cache
npm cache clean --force

# Remove node_modules and reinstall
rm -rf node_modules package-lock.json
npm install
```

### **Environment Variables:**
Make sure these are set correctly:
```bash
export ANDROID_HOME=$HOME/Library/Android/sdk
export PATH=$PATH:$ANDROID_HOME/emulator
export PATH=$PATH:$ANDROID_HOME/platform-tools
```

## 📦 Dependencies

### **Core Dependencies:**
- `expo`: ~53.0.20
- `react`: 19.0.0
- `react-native`: 0.79.5
- `react-i18next`: Latest
- `i18next`: Latest
- `@react-native-async-storage/async-storage`: Latest

### **Development Dependencies:**
- `typescript`: ~5.8.3
- `@types/react`: ~19.0.10
- `@babel/core`: ^7.25.2

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch: `git checkout -b feature-name`
3. Make your changes
4. Add tests if applicable
5. Commit your changes: `git commit -m 'Add feature'`
6. Push to the branch: `git push origin feature-name`
7. Submit a pull request

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🆘 Support

If you encounter any issues:

1. Check the [troubleshooting section](#-troubleshooting)
2. Search existing [GitHub issues](https://github.com/your-repo/issues)
3. Create a new issue with detailed information
4. Include your environment details (OS, Node.js version, etc.)

## 🔗 Useful Links

- [Expo Documentation](https://docs.expo.dev/)
- [React Native Documentation](https://reactnative.dev/)
- [React i18next Documentation](https://react.i18next.com/)
- [Expo SDK 53 Release Notes](https://docs.expo.dev/versions/v53.0.0/)

---

**Happy coding! 🚀**
