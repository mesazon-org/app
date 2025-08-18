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
import { Linking } from "react-native";

type CheckMailScreenNavigationProp = NativeStackNavigationProp<
  RootStackParamList,
  typeof SCREEN_NAMES.CHECK_EMAIL
>;

export default function CheckMail() {
  const { t } = useTranslation();
  const navigation = useNavigation<CheckMailScreenNavigationProp>();

  const handleOpenEmailApp = () => {
    // Open the default email app
    Linking.openURL('mailto:');
  };

  const handleSkip = () => {
    // Navigate to next screen or handle skip logic
    console.log("User skipped email confirmation");
    // navigation.navigate(SCREEN_NAMES.NEXT_SCREEN);
  };

  const handleTryAnotherEmail = () => {
    // Navigate back to forgot password or email input screen
    navigation.navigate(SCREEN_NAMES.FORGOT_PASSWORD);
  };

  return (
    <Layout>
      <Spacer style={{ flex: 0.5 }} />
      
      <View style={styles.iconContainer}>
        <View style={styles.mailIcon}>
          <Text style={styles.envelopeIcon}>✉️</Text>
          <View style={styles.checkmarkOverlay}>
            <Text style={styles.checkmarkIcon}>✓</Text>
          </View>
        </View>
      </View>

      <Title style={{ textAlign: "center" }}>{t("check-mail")}</Title>
      <Subtitle style={{ textAlign: "center" }}>
        {t("password-recovery-sent")}
      </Subtitle>

      <FormButton onPress={handleOpenEmailApp} disabled={false}>
        {t("open-email-app")}
      </FormButton>

      <TouchableOpacity onPress={handleSkip} style={styles.skipButton}>
        <Text style={styles.skipText}>{t("skip-confirm-later")}</Text>
      </TouchableOpacity>

      <Spacer />

      <View style={styles.footerContainer}>
        <Text style={styles.footerText}>
          {t("did-not-receive-email")}{" "}
          <Text style={styles.footerTextBold}>
            {t("check-your-spam-folder")}
          </Text>
        </Text>
        <View style={{ flexDirection: "row", alignItems: "center", gap: 2, justifyContent: "center", marginTop: 8 }}>
            <Text style={styles.orText}>{t("or")}</Text>
            <TouchableOpacity onPress={handleTryAnotherEmail}>
                <Text style={styles.footerLink}>{t("try-another-email")}</Text>
            </TouchableOpacity>
        </View>
      </View>
    </Layout>
  );
}

const styles = StyleSheet.create({
  iconContainer: {
    alignItems: "center",
    marginBottom: 20,
  },
  mailIcon: {
    width: 80,
    height: 80,
    backgroundColor: "#E8F5E8",
    borderRadius: 20,
    alignItems: "center",
    justifyContent: "center",
    position: "relative",
  },
  envelopeIcon: {
    fontSize: 40,
    color: "#6DBE45",
  },
  checkmarkOverlay: {
    position: "absolute",
    bottom: 5,
    right: 5,
    width: 24,
    height: 24,
    backgroundColor: "#6DBE45",
    borderRadius: 12,
    alignItems: "center",
    justifyContent: "center",
  },
  checkmarkIcon: {
    fontSize: 14,
    color: "#FFFFFF",
    fontWeight: "bold",
  },
  skipButton: {
    alignItems: "center",
    marginTop: 16,
  },
  skipText: {
    color: "#6DBE45",
    fontSize: 16,
    fontWeight: "500",
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
  footerTextBold: {
    fontWeight: "bold",
    color: "#333333",
  },
  footerLink: {
    color: "#6DBE45",
    fontWeight: "bold",
    fontSize: 14,
    textDecorationLine: "underline",
  },
  orText: {
    textTransform: 'lowercase'
  }
});