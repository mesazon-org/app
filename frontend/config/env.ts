import { cleanEnv, str, url } from 'envalid';

const DEFAULT_SUPABASE_URL = "http://localhost:8000";

const env = cleanEnv(process.env, {
    EXPO_PUBLIC_SUPABASE_URL: url({ default: DEFAULT_SUPABASE_URL }),
    EXPO_PUBLIC_SUPABASE_KEY: str(),
    EXPO_PUBLIC_APP_URL: url()
  });
  
  export default env;