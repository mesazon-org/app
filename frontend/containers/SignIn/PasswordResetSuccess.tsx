import Title from "@/components/Title";
import Layout from "../Layout";
import Subtitle from "@/components/Subtitle";
import { useTranslation } from "react-i18next";
import FormButton from "@/components/FormButton";
import Spacer from "@/components/Spacer";
import { Text, TouchableOpacity, View, StyleSheet } from "react-native";
import { RootStackParamList, SCREEN_NAMES } from "@/services/navigation";
import { useNavigation } from "@react-navigation/native";
import { NativeStackNavigationProp } from "@react-navigation/native-stack";

type PasswordChangedScreenNavigationProp = NativeStackNavigationProp<
  RootStackParamList,
  typeof SCREEN_NAMES.PASSWORD_RESET_SUCCESS
>;

export default function PasswordResetSuccess() {
  const { t } = useTranslation();
  const navigation = useNavigation<PasswordChangedScreenNavigationProp>();

  const handleSignIn = () => {
    navigation.navigate(SCREEN_NAMES.SIGN_IN);
  };

  return (
    <Layout>
      <Spacer style={{ flex: 0.5 }} />
      
      <View style={styles.iconContainer}>
        <View style={styles.successIcon}>
          <Text style={styles.checkmarkIcon}>✓</Text>
        </View>
      </View>

      <Title style={{ textAlign: "center" }}>{t("password-changed")}</Title>
      <Subtitle style={{ textAlign: "center" }}>
        {t("password-changed-subtitle")}
      </Subtitle>

      <FormButton onPress={handleSignIn} disabled={false}>
        {t("back-to-login")}
      </FormButton>

      <Spacer />      
    </Layout>
  );
}

const styles = StyleSheet.create({
  iconContainer: {
    alignItems: "center",
    marginBottom: 20,
  },
  successIcon: {
    width: 80,
    height: 80,
    backgroundColor: "#E8F5E8",
    borderRadius: 40,
    alignItems: "center",
    justifyContent: "center",
  },
  checkmarkIcon: {
    fontSize: 40,
    color: "#6DBE45",
    fontWeight: "bold",
  },
  footerContainer: {
    alignItems: "center",
    paddingHorizontal: 20,
  },
  footerText: {
    textAlign: "center",
    color: "#666666",
    fontSize: 14,
    lineHeight: 20,
  },
});