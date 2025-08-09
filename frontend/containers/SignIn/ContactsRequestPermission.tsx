import { View, Text } from "react-native";
import Layout from "../Layout";
import useContacts from "@/hooks/useContacts";
import { useEffect } from "react";
import { RootStackParamList, SCREEN_NAMES } from "@/services/navigation";
import { useNavigation } from "@react-navigation/native";
import { NativeStackNavigationProp } from "@react-navigation/native-stack";

type CreateUserDetailsScreenNavigationProp = NativeStackNavigationProp<RootStackParamList, typeof SCREEN_NAMES.SELECT_CONTACTS>;

export default function ContactsRequestPermission() {
  const { hasPermission } = useContacts();
  const navigation = useNavigation<CreateUserDetailsScreenNavigationProp>();

  useEffect(() => {
    hasPermission().then((hasPermission) => {
      if (hasPermission) {
        navigation.navigate(SCREEN_NAMES.SELECT_CONTACTS);
      }
    });
  }, []);

  return (
    <Layout>
      {/* <Header currentStep={1} totalSteps={3} showBackButton={true} /> */}

      <Text>ContactsRequestPermission</Text>
    </Layout>
  );
}
