# Internationalization (i18n) Setup

This React Expo app has been configured with internationalization using `react-i18next`.

## Features

- ✅ Multi-language support (English, Spanish, French)
- ✅ Language detection and persistence
- ✅ Easy language switching
- ✅ Type-safe translations
- ✅ Custom hooks for i18n functionality

## File Structure

```
├── i18n/
│   ├── index.ts              # Main i18n configuration
│   └── locales/
│       ├── en.json           # English translations
│       ├── es.json           # Spanish translations
│       └── fr.json           # French translations
├── components/
│   └── LanguageSwitcher.tsx  # Language switcher component
├── hooks/
│   └── useI18n.ts           # Custom i18n hook
└── types/
    └── json.d.ts            # TypeScript declarations
```

## Usage

### Basic Translation

```tsx
import { useTranslation } from 'react-i18next';

function MyComponent() {
  const { t } = useTranslation();
  
  return <Text>{t('welcome')}</Text>;
}
```

### Using the Custom Hook

```tsx
import { useI18n } from './hooks/useI18n';

function MyComponent() {
  const { t, changeLanguage, getCurrentLanguage } = useI18n();
  
  return (
    <View>
      <Text>{t('welcome')}</Text>
      <Button onPress={() => changeLanguage('es')} title="Switch to Spanish" />
      <Text>Current: {getCurrentLanguage()}</Text>
    </View>
  );
}
```

### Language Switcher Component

The `LanguageSwitcher` component provides a UI for switching between languages:

```tsx
import LanguageSwitcher from './components/LanguageSwitcher';

function App() {
  return (
    <View>
      <LanguageSwitcher />
      {/* Your app content */}
    </View>
  );
}
```

## Adding New Languages

1. Create a new translation file in `i18n/locales/` (e.g., `de.json` for German)
2. Add the language to the resources object in `i18n/index.ts`
3. Update the `LanguageSwitcher` component to include the new language

### Example: Adding German

1. Create `i18n/locales/de.json`:
```json
{
  "welcome": "Willkommen in Ihrer App!",
  "startWorking": "Öffnen Sie App.tsx, um mit Ihrer App zu arbeiten!"
}
```

2. Update `i18n/index.ts`:
```tsx
import de from './locales/de.json';

const resources = {
  // ... existing languages
  de: {
    translation: de,
  },
};
```

3. Update `LanguageSwitcher.tsx`:
```tsx
const languages = [
  { code: 'en', name: 'English' },
  { code: 'es', name: 'Español' },
  { code: 'fr', name: 'Français' },
  { code: 'de', name: 'Deutsch' }, // Add this line
];
```

## Translation Keys

The app includes common translation keys:

- `welcome` - Welcome message
- `startWorking` - Instructions to start working
- `language` - Language label
- `settings` - Settings
- `home` - Home
- `profile` - Profile
- `about` - About
- `contact` - Contact
- `login` - Login
- `logout` - Logout
- `email` - Email
- `password` - Password
- `submit` - Submit
- `cancel` - Cancel
- `save` - Save
- `delete` - Delete
- `edit` - Edit
- `loading` - Loading
- `error` - Error message
- `success` - Success message
- `confirm` - Confirm
- `back` - Back
- `next` - Next
- `previous` - Previous
- `search` - Search
- `filter` - Filter
- `sort` - Sort
- `refresh` - Refresh
- `retry` - Retry
- `close` - Close

## Configuration

The i18n configuration includes:

- **Language Detection**: Automatically detects user's preferred language
- **Fallback**: Falls back to English if translation is missing
- **Persistence**: Saves language preference in localStorage
- **Debug Mode**: Enabled in development mode

## Best Practices

1. **Use translation keys**: Always use keys instead of hardcoded strings
2. **Keep translations organized**: Group related translations together
3. **Use interpolation**: For dynamic content, use interpolation:
   ```tsx
   t('greeting', { name: 'John' })
   ```
4. **Pluralization**: Use pluralization for different counts:
   ```tsx
   t('items', { count: 5 })
   ```

## Testing

To test the i18n setup:

1. Run the app: `npm start`
2. Use the language switcher to change languages
3. Verify that all text updates correctly
4. Check that language preference persists after app restart 