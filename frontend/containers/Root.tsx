import "../i18n";
import { createNativeStackNavigator } from "@react-navigation/native-stack";
import { createStaticNavigation } from "@react-navigation/native";
import SignIn from "./SignIn";

const RootStack = createNativeStackNavigator({
  screens: {
    SignIn: SignIn,
  },
});

const Navigation = createStaticNavigation(RootStack);

export default function Root() {
  return <Navigation />;
}
