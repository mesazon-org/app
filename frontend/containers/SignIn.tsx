import React, { useState } from "react";
import {
  View,
  Text,
  Image,
  TextInput,
  TouchableOpacity,
  StyleSheet,
  SafeAreaView,
  KeyboardAvoidingView,
  Platform,
  ScrollView,
} from "react-native";
import { useTranslation } from "react-i18next";

interface SignInFormData {
  email: string;
  password: string;
}

export default function SignIn() {
  const { t } = useTranslation();
  const [formData, setFormData] = useState<SignInFormData>({
    email: "",
    password: "",
  });

  const handleInputChange = (field: keyof SignInFormData, value: string) => {
    setFormData((prev) => ({ ...prev, [field]: value }));
  };

  const handleSignIn = () => {
    // TODO: Implement sign in logic
    console.log("Sign in with:", formData);
  };

  const handleForgotPassword = () => {
    // TODO: Implement forgot password logic
    console.log("Forgot password");
  };

  return (
    <SafeAreaView style={styles.container}>
      <KeyboardAvoidingView
        behavior={Platform.OS === "ios" ? "padding" : "height"}
        style={styles.keyboardAvoidingView}
      >
        <ScrollView
          style={styles.scrollView}
          contentContainerStyle={styles.scrollViewContent}
          showsVerticalScrollIndicator={false}
          keyboardShouldPersistTaps="handled"
        >
          <View style={styles.content}>
            <Image
              source={require('@/assets/Farmers.png')}
              style={styles.logo}
              resizeMode="contain"
            />

            <Text style={styles.title}>{t("sign-in")}</Text>

            <View style={styles.form}>
              <View style={styles.inputContainer}>
                <Text style={styles.label}>{t("email")}</Text>
                <TextInput
                  value={formData.email}
                  onChangeText={(value) => handleInputChange("email", value)}
                  placeholder={t("email")}
                  style={styles.input}
                  keyboardType="email-address"
                  autoCapitalize="none"
                  autoCorrect={false}
                />
              </View>

              <View style={styles.inputContainer}>
                <Text style={styles.label}>{t("password")}</Text>
                <TextInput
                  value={formData.password}
                  onChangeText={(value) => handleInputChange("password", value)}
                  placeholder={t("password")}
                  style={styles.input}
                  secureTextEntry
                  autoCapitalize="none"
                  autoCorrect={false}
                />
                <TouchableOpacity
                  style={styles.forgotPasswordContainer}
                  onPress={handleForgotPassword}
                >
                  <Text style={styles.forgotPasswordText}>
                    {t("forgot-password")}
                  </Text>
                </TouchableOpacity>
              </View>

              <TouchableOpacity
                style={styles.signInButton}
                onPress={handleSignIn}
                activeOpacity={0.8}
              >
                <Text style={styles.signInButtonText}>{t("sign-in")}</Text>
              </TouchableOpacity>

              <View style={styles.orContainer}>
                <View style={styles.orLine} />
                <Text style={styles.orText}>Or</Text>
                <View style={styles.orLine} />
              </View>

              <View style={styles.socialButtonsContainer}>
                <TouchableOpacity>
                  <Image source={require('@/assets/Google.png')} />
                </TouchableOpacity>
                <TouchableOpacity>
                  <Image source={require('@/assets/Facebook.png')} />
                </TouchableOpacity>
              </View>

              <View style={styles.signUpContainer}>
                <Text style={styles.signUpText}>
                  Don't have an account?{" "}
                  <Text style={styles.signUpLink}>Sign Up</Text>
                </Text>
              </View>
            </View>
          </View>
        </ScrollView>
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: "#FFFFFF",
  },
  keyboardAvoidingView: {
    flex: 1,
  },
  scrollView: {
    flex: 1,
  },
  scrollViewContent: {
    flexGrow: 1,
    paddingBottom: 20,
  },
  content: {
    flex: 1,
    paddingHorizontal: 35,
    paddingVertical: 20,
  },
  logo: {
    width: 360,
    height: 294,
    alignSelf: "center",
    marginBottom: 20
  },
  title: {
    fontSize: 46,
    fontWeight: "bold",
    letterSpacing: 1,
    marginBottom: 30,
  },
  form: {
    gap: 16,
  },
  inputContainer: {
    gap: 8,
  },
  label: {
    fontWeight: "500",
    color: "#333",
  },
  input: {
    borderWidth: 1,
    borderColor: "#6DBE45",
    padding: 12,
    borderRadius: 8,
    fontSize: 16,
  },
  forgotPasswordContainer: {
    alignItems: "flex-end",
  },
  forgotPasswordText: {
    fontSize: 14,
    color: "#6DBE45",
    fontWeight: 'medium',
  },
  signInButton: {
    backgroundColor: "#6DBE45",
    alignItems: "center",
    justifyContent: "center",
    paddingVertical: 16,
    borderRadius: 8,
    marginTop: 20,
  },
  signInButtonText: {
    color: "#FFFFFF",
    fontWeight: "bold",
    fontSize: 16,
  },
  orContainer: {
    flexDirection: "row",
    alignItems: "center",
    marginVertical: 20,
  },
  orLine: {
    flex: 1,
    height: 1,
    backgroundColor: "#6DBE45",
  },
  orText: {
    marginHorizontal: 15,
    fontSize: 16,
    fontWeight: "bold",
    color: "#000",
  },
  socialButtonsContainer: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "center",
    gap: 24,
    marginBottom: 20,
  },
  socialButton: {
    flex: 1,
    backgroundColor: "#FFFFFF",
    borderWidth: 1,
    borderColor: "#6DBE45",
    borderRadius: 8,
    paddingVertical: 12,
    alignItems: "center",
    justifyContent: "center",
  },
  socialButtonText: {
    color: "#6DBE45",
    fontWeight: "500",
    fontSize: 14,
  },
  signUpContainer: {
    alignItems: "center",
    marginTop: 10,
  },
  signUpText: {
    fontSize: 14,
    color: "#666",
  },
  signUpLink: {
    fontWeight: "bold",
    color: "#000000",
  },
});
