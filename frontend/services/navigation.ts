export enum SCREEN_NAMES {
  SIGN_IN = 'SignIn',
  CREATE_USER_DETAILS = 'CreateUserDetails',
  CONTACTS_REQUEST_PERMISSION = 'ContactsRequestPermission',
  SELECT_CONTACTS = 'SelectContacts',
  // Add more screens as you create them
  // DASHBOARD = 'Dashboard',
  // PROFILE = 'Profile',
  // SETTINGS = 'Settings',
}

export type RootStackParamList = {
  [SCREEN_NAMES.SIGN_IN]: undefined;
  [SCREEN_NAMES.CREATE_USER_DETAILS]: undefined;
  [SCREEN_NAMES.CONTACTS_REQUEST_PERMISSION]: undefined;
  [SCREEN_NAMES.SELECT_CONTACTS]: undefined;
  // Add more screen types as you create them
  // [SCREEN_NAMES.DASHBOARD]: undefined;
  // [SCREEN_NAMES.PROFILE]: { userId?: string };
  // [SCREEN_NAMES.SETTINGS]: undefined;
};

// Navigation helper functions for React Native
export const NavigationService = {
  // You can add navigation methods here if needed
  // For example, deep linking, navigation state management, etc.
};

// Screen options for consistent styling
export const screenOptions = {
  headerShown: false,
  // Add more default options as needed
}; 