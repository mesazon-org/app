import "@/i18n";
import { createNativeStackNavigator } from "@react-navigation/native-stack";
import { createStaticNavigation } from "@react-navigation/native";
import { SCREEN_NAMES, RootStackParamList, screenOptions } from "@/services/navigation";
import SignIn from "@/containers/SignIn/SignIn";
import CreateUserDetails from "@/containers/SignIn/CreateUserDetails";
import ContactsRequestPermission from "@/containers/SignIn/ContactsRequestPermission";
import SelectContacts from "./SignIn/SelectContacts";
import WelcomeUser from "./SignIn/WelcomeUser";
import ForgotPassword from "./SignIn/ForgotPassword";
import CheckMail from "./SignIn/CheckMail";
import ResetPassword from "./SignIn/ResetPassword";
import PasswordResetSuccess from "./SignIn/PasswordResetSuccess";

const RootStack = createNativeStackNavigator<RootStackParamList>({
  screens: {
    [SCREEN_NAMES.SIGN_IN]: {
      screen: SignIn,
      options: screenOptions,
    },
    [SCREEN_NAMES.CREATE_USER_DETAILS]: {
      screen: CreateUserDetails,
      options: screenOptions,
    },
    [SCREEN_NAMES.CONTACTS_REQUEST_PERMISSION]: {
      screen: ContactsRequestPermission,
      options: screenOptions,
    },
    [SCREEN_NAMES.SELECT_CONTACTS]: {
      screen: SelectContacts,
      options: screenOptions,
    },
    [SCREEN_NAMES.WELCOME_USER]: {
      screen: WelcomeUser,
      options: screenOptions,
    },
    [SCREEN_NAMES.FORGOT_PASSWORD]: {
      screen: ForgotPassword,
      options: screenOptions,
    },
    [SCREEN_NAMES.CHECK_EMAIL]: {
      screen: CheckMail,
      options: screenOptions,
    },
    [SCREEN_NAMES.RESET_PASSWORD]: {
      screen: ResetPassword,
      options: screenOptions,
    },
    [SCREEN_NAMES.PASSWORD_RESET_SUCCESS]: {
      screen: PasswordResetSuccess,
      options: screenOptions,
    },
    // [SCREEN_NAMES.PASSWORD_RESET_FAILED]: {
    //   screen: PasswordResetFailed,
    //   options: screenOptions,
    // },
  },
});

const Navigation = createStaticNavigation(RootStack);

export default function Root() {
  return <Navigation />;
}
