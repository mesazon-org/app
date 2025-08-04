import "@/i18n";
import { createNativeStackNavigator } from "@react-navigation/native-stack";
import { createStaticNavigation } from "@react-navigation/native";
import { SCREEN_NAMES, RootStackParamList, screenOptions } from "@/services/navigation";
import SignIn from "@/containers/SignIn/SignIn";
import CreateUserDetails from "@/containers/SignIn/CreateUserDetails";

const RootStack = createNativeStackNavigator<RootStackParamList>({
  screens: {
    [SCREEN_NAMES.SIGN_IN]: {
      screen: SignIn,
      options: screenOptions,
    },
    [SCREEN_NAMES.CREATE_USER_DETAILS]: {
      screen: CreateUserDetails,
      options: screenOptions,
    }   
  },
});

const Navigation = createStaticNavigation(RootStack);

export default function Root() {
  return <Navigation />;
}
