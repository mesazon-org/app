import './i18n';
import Root from './containers/Root';
import UserProvider from '@/providers/User/UserProvider';


export default function App() {
  return (
    <UserProvider>
      <Root />
    </UserProvider>
  );
}